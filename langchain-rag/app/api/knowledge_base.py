"""Knowledge base management routes."""

from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from app.core import db, store
from app.core.auth import get_current_user
from app.core.permissions import require_admin, require_kb_read, require_kb_write
from app.models.schemas import (
    ChunkListResponse,
    KnowledgeBaseCreate,
    KnowledgeBaseResponse,
)
from app.services.embedding import get_embedding_dimension
from app.services.graph import delete_graph_data
from app.services.retrieval import create_collection, drop_collection

router = APIRouter(prefix="/api/knowledge-bases", tags=["knowledge-bases"])


def _to_response(kb: dict, document_count: int) -> KnowledgeBaseResponse:
    return KnowledgeBaseResponse(
        id=kb["id"],
        name=kb["name"],
        description=kb["description"],
        kb_type=kb["kb_type"],
        document_count=document_count,
        created_at=db.parse_iso(kb["created_at"]),
        owner=kb["owner"],
        is_global=bool(kb["is_global"]),
    )


@router.post("", response_model=KnowledgeBaseResponse)
async def create_knowledge_base(
    payload: KnowledgeBaseCreate,
    current_user: dict = Depends(get_current_user),
):
    """Create a new knowledge base and its associated Milvus collection."""
    if payload.is_global:
        require_admin(current_user)

    if payload.kb_type not in ("document", "book", "project"):
        raise HTTPException(
            status_code=400,
            detail="kbType must be one of: document, book, project",
        )

    kb_id = store.new_id()

    # The dimension is fixed at creation time: it is baked into the Milvus
    # schema, so it must be recorded and reused rather than assumed later.
    dimension = get_embedding_dimension()
    try:
        create_collection(kb_id, dimension=dimension)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail="Failed to create Milvus collection: " + str(e),
        )

    kb = store.create_kb(
        kb_id=kb_id,
        name=payload.name,
        description=payload.description,
        kb_type=payload.kb_type,
        owner=current_user["username"],
        is_global=payload.is_global,
        embedding_dim=dimension,
    )

    return _to_response(kb, 0)


@router.get("", response_model=List[KnowledgeBaseResponse])
async def list_knowledge_bases(current_user: dict = Depends(get_current_user)):
    """List knowledge bases: all global + user's own private ones."""
    kbs = store.list_visible_kbs(current_user["username"], current_user["role"])
    counts = store.kb_document_counts()
    return [_to_response(kb, counts.get(kb["id"], 0)) for kb in kbs]


@router.get("/{kb_id}", response_model=KnowledgeBaseResponse)
async def get_knowledge_base(
    kb_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Get a specific knowledge base by ID."""
    kb = require_kb_read(kb_id, current_user)
    return _to_response(kb, store.kb_document_count(kb_id))


@router.delete("/{kb_id}")
async def delete_knowledge_base(
    kb_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Delete a knowledge base, its Milvus collection and its graph data."""
    require_kb_write(kb_id, current_user)

    try:
        drop_collection(kb_id)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail="Failed to drop Milvus collection: " + str(e),
        )

    try:
        delete_graph_data(kb_id)
    except Exception:
        pass  # Non-critical: graph data cleanup is best-effort

    # Cascades to documents and chunks.
    store.delete_kb(kb_id)

    return {"message": "Knowledge base deleted successfully"}


@router.get("/{kb_id}/chunks", response_model=ChunkListResponse)
async def get_chunks(
    kb_id: str,
    page: int = Query(default=1, ge=1),
    # camelCase per the contract -- the frontend sends `pageSize`, and reading
    # it as `page_size` silently pinned the page size to the default of 20.
    page_size: int = Query(default=20, ge=1, le=100, alias="pageSize"),
    search: Optional[str] = Query(default=None),
    current_user: dict = Depends(get_current_user),
):
    """Paginated chunk listing with infix text search.

    Served from SQLite, not Milvus: Milvus 2.4's `like` is prefix-only, so
    `%keyword%` search cannot be expressed there.
    """
    require_kb_read(kb_id, current_user)

    chunks, total = store.list_chunks(
        kb_id, page=page, page_size=page_size, search=search
    )

    return ChunkListResponse(
        chunks=chunks,
        total=total,
        page=page,
        page_size=page_size,
    )
