"""Knowledge graph visualization and query routes."""

from fastapi import APIRouter, Depends, HTTPException

from app.core.auth import get_current_user
from app.core.permissions import require_kb_read
from app.models.schemas import GraphData, GraphQueryRequest
from app.services.graph import get_full_graph, query_graph

router = APIRouter(prefix="/api/graph", tags=["graph"])


@router.get("/{kb_id}", response_model=GraphData)
async def get_graph(kb_id: str, current_user: dict = Depends(get_current_user)):
    """Get the full knowledge graph for a knowledge base."""
    require_kb_read(kb_id, current_user)

    try:
        return get_full_graph(kb_id)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail="Failed to retrieve graph data: " + str(e),
        )


@router.post("/query", response_model=GraphData)
async def query_entity_relationships(
    request: GraphQueryRequest,
    current_user: dict = Depends(get_current_user),
):
    """Query specific entity relationships in the knowledge graph."""
    require_kb_read(request.knowledge_base_id, current_user)

    if not request.entity.strip():
        raise HTTPException(status_code=400, detail="Entity name cannot be empty")

    try:
        return query_graph(request.knowledge_base_id, request.entity)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail="Failed to query graph: " + str(e),
        )
