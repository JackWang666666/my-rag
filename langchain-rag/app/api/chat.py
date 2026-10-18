"""Chat routes with SSE streaming support and session management."""

import json
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import Field
from sse_starlette.sse import EventSourceResponse

from app.core.auth import get_current_user
from app.core.permissions import require_kb_read, require_session_access
from app.models.schemas import (
    CamelModel,
    ChatHistoryResponse,
    ChatRequest,
    SessionListResponse,
    SessionResponse,
)
from app.services.chat import (
    ALL_KB_SENTINEL,
    chat_stream,
    create_session,
    delete_session,
    get_history,
    get_session,
    list_sessions,
)

router = APIRouter(prefix="/api", tags=["chat"])


class CreateSessionRequest(CamelModel):
    """Request model for creating a session explicitly."""

    knowledge_base_id: str
    title: str = Field(default="New Session", max_length=200)


@router.post("/chat")
async def chat(request: ChatRequest, current_user: dict = Depends(get_current_user)):
    """
    Chat endpoint with Server-Sent Events (SSE) streaming.

    Sends events: session, metadata, confidence, chunk, sources, done, error.
    See docs/CONTRACT.md section 4.
    """
    # The sentinel is not a real KB, so there is no ownership to check -- the
    # pipeline resolves it to the caller's own KBs internally.
    if request.knowledge_base_id == ALL_KB_SENTINEL:
        kb = {"kb_type": "document", "name": ""}
    else:
        kb = require_kb_read(request.knowledge_base_id, current_user)

    # Continuing an existing session requires owning it.
    if request.session_id:
        session = require_session_access(request.session_id, current_user)
        if session["kb_id"] != request.knowledge_base_id:
            raise HTTPException(
                status_code=400,
                detail="Session belongs to a different knowledge base",
            )

    async def event_generator():
        async for event in chat_stream(
            question=request.question,
            kb_id=request.knowledge_base_id,
            session_id=request.session_id,
            kb_type=kb["kb_type"],
            kb_name=kb["name"],
            username=current_user["username"],
        ):
            data = event.get("data", "")

            # Serialize complex data as JSON
            if isinstance(data, (dict, list)):
                data = json.dumps(data, ensure_ascii=False)

            yield {
                "event": event.get("event", "chunk"),
                "data": data,
            }

    return EventSourceResponse(event_generator())


@router.get("/chat/history/{session_id}", response_model=ChatHistoryResponse)
async def get_chat_history(
    session_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Get chat history for a session the caller owns."""
    require_session_access(session_id, current_user)

    # An empty history is a valid state for a freshly created session, so this
    # returns 200 with an empty list rather than 404.
    return ChatHistoryResponse(
        session_id=session_id,
        messages=get_history(session_id),
    )


# --- Session Management ---


@router.get("/sessions", response_model=SessionListResponse)
async def list_all_sessions(
    knowledge_base_id: Optional[str] = Query(default=None, alias="knowledgeBaseId"),
    current_user: dict = Depends(get_current_user),
):
    """List the caller's own sessions, optionally filtered by knowledge base.

    Admins see every session. Regular users only ever see their own.
    """
    sessions = list_sessions(
        owner=current_user["username"],
        role=current_user["role"],
        knowledge_base_id=knowledge_base_id,
    )
    return SessionListResponse(sessions=sessions)


@router.post("/sessions", response_model=SessionResponse)
async def create_new_session(
    request: CreateSessionRequest,
    current_user: dict = Depends(get_current_user),
):
    """Create a new session explicitly."""
    require_kb_read(request.knowledge_base_id, current_user)

    return create_session(
        knowledge_base_id=request.knowledge_base_id,
        owner=current_user["username"],
        title=request.title,
    )


@router.get("/sessions/{session_id}", response_model=SessionResponse)
async def get_session_detail(
    session_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Get session detail by ID."""
    require_session_access(session_id, current_user)
    session = get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return session


@router.delete("/sessions/{session_id}")
async def delete_session_endpoint(
    session_id: str,
    current_user: dict = Depends(get_current_user),
):
    """Delete a session and its chat history."""
    require_session_access(session_id, current_user)
    delete_session(session_id)
    return {"message": "Session deleted successfully"}
