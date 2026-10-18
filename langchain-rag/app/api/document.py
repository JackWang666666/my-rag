"""Document upload, parsing, and management routes.

Uploads return immediately with ``status="processing"``; the heavy work happens
in a background task (app.services.ingest) and the frontend polls
``GET /api/documents/{id}``.
"""

import logging
import os
import tempfile
from typing import List, Optional

import aiofiles
from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    Form,
    HTTPException,
    UploadFile,
)

from app.core import db, store
from app.core.auth import get_current_user
from app.core.config import settings
from app.core.permissions import require_document_access, require_kb_read, require_kb_write
from app.models.schemas import DocumentResponse
from app.services.ingest import SUPPORTED_TYPES, ingest_file, ingest_project
from app.services.retrieval import delete_by_document

logger = logging.getLogger(__name__)

router = APIRouter(tags=["documents"])

# Read uploads in chunks so a large file never has to fit in memory at once.
READ_CHUNK = 1024 * 1024


def _to_response(doc: dict) -> DocumentResponse:
    return DocumentResponse(
        id=doc["id"],
        filename=doc["filename"],
        file_type=doc["file_type"],
        file_size=doc["file_size"],
        chunk_count=doc["chunk_count"],
        status=doc["status"],
        stage=doc["stage"],
        error=doc["error"],
        created_at=db.parse_iso(doc["created_at"]),
        knowledge_base_id=doc["kb_id"],
    )


async def _save_upload(file: UploadFile, dest_path: str) -> int:
    """Stream an upload to disk, enforcing MAX_UPLOAD_MB. Returns bytes written.

    Reading the whole body with `await file.read()` let a single upload allocate
    its full size in RAM, and MAX_FILE_SIZE from .env.example was never checked.
    """
    max_bytes = max(1, settings.MAX_UPLOAD_MB) * 1024 * 1024
    written = 0
    try:
        async with aiofiles.open(dest_path, "wb") as out:
            while True:
                chunk = await file.read(READ_CHUNK)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(
                        status_code=413,
                        detail="File exceeds the %d MB limit" % settings.MAX_UPLOAD_MB,
                    )
                await out.write(chunk)
    except HTTPException:
        _cleanup(dest_path)
        raise
    except Exception as e:
        _cleanup(dest_path)
        raise HTTPException(status_code=500, detail="Failed to save file: " + str(e))

    if written == 0:
        _cleanup(dest_path)
        raise HTTPException(status_code=400, detail="Uploaded file is empty")

    return written


def _cleanup(path: str) -> None:
    try:
        if os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


@router.post(
    "/api/knowledge-bases/{kb_id}/documents",
    response_model=DocumentResponse,
)
async def upload_document(
    kb_id: str,
    file: UploadFile,
    background_tasks: BackgroundTasks,
    current_user: dict = Depends(get_current_user),
):
    """Accept a file and queue it for parsing, chunking, embedding and storage."""
    require_kb_write(kb_id, current_user)

    if not file.filename:
        raise HTTPException(status_code=400, detail="No filename provided")

    file_ext = os.path.splitext(file.filename)[1].lower()
    if file_ext not in SUPPORTED_TYPES:
        raise HTTPException(
            status_code=400,
            detail="Unsupported file type: %s. Supported: %s"
            % (file_ext, sorted(SUPPORTED_TYPES)),
        )

    upload_dir = os.path.abspath(settings.UPLOAD_DIR)
    os.makedirs(upload_dir, exist_ok=True)

    doc_id = store.new_id()
    file_path = os.path.join(upload_dir, doc_id + file_ext)

    file_size = await _save_upload(file, file_path)

    doc = store.create_document(
        doc_id=doc_id,
        kb_id=kb_id,
        filename=file.filename,
        file_type=file_ext,
        file_size=file_size,
        status="processing",
    )

    background_tasks.add_task(
        ingest_file, kb_id, doc_id, file_path, file_ext, file.filename
    )

    return _to_response(doc)


@router.post(
    "/api/knowledge-bases/{kb_id}/import-project",
    response_model=DocumentResponse,
)
async def import_project(
    kb_id: str,
    file: UploadFile,
    background_tasks: BackgroundTasks,
    file_extensions: Optional[str] = Form(default=None, alias="fileExtensions"),
    current_user: dict = Depends(get_current_user),
):
    """
    Import a code project from a ZIP file.

    Extracts the ZIP (rejecting entries that escape the target directory),
    scans code files respecting .gitignore, generates a project structure
    overview, and queues everything through the embedding + graph pipeline.

    Args:
        kb_id: Knowledge base ID.
        file: ZIP file containing the project.
        file_extensions: Optional comma-separated list of extensions to include
                        (e.g. ".py,.js,.ts"). If not provided, uses defaults.
    """
    require_kb_write(kb_id, current_user)

    if not file.filename or not file.filename.lower().endswith(".zip"):
        raise HTTPException(status_code=400, detail="File must be a ZIP archive")

    # The temp dir is removed by ingest_project once it finishes.
    temp_dir = tempfile.mkdtemp(prefix="rag-import-")
    zip_path = os.path.join(temp_dir, "project.zip")

    file_size = await _save_upload(file, zip_path)

    doc_id = store.new_id()
    doc = store.create_document(
        doc_id=doc_id,
        kb_id=kb_id,
        filename=file.filename,
        file_type=".zip",
        file_size=file_size,
        status="processing",
    )

    background_tasks.add_task(
        ingest_project, kb_id, doc_id, zip_path, temp_dir, file_extensions
    )

    return _to_response(doc)


@router.get(
    "/api/knowledge-bases/{kb_id}/documents",
    response_model=List[DocumentResponse],
)
async def list_documents(
    kb_id: str,
    current_user: dict = Depends(get_current_user),
):
    """List all documents in a knowledge base."""
    require_kb_read(kb_id, current_user)
    return [_to_response(doc) for doc in store.list_documents(kb_id)]


@router.get("/api/documents/{doc_id}", response_model=DocumentResponse)
async def get_document(
    doc_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Get one document. Used by the frontend to poll ingestion status."""
    doc = require_document_access(doc_id, current_user)
    return _to_response(doc)


@router.delete("/api/documents/{doc_id}")
async def delete_document(
    doc_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Delete a document, its vectors in Milvus and its chunk rows."""
    doc = require_document_access(doc_id, current_user, write=True)
    kb_id = doc["kb_id"]

    try:
        delete_by_document(kb_id, doc_id)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail="Failed to delete vectors: " + str(e),
        )

    upload_dir = os.path.abspath(settings.UPLOAD_DIR)
    _cleanup(os.path.join(upload_dir, doc_id + doc["file_type"]))

    store.delete_document(doc_id)

    return {"message": "Document deleted successfully"}
