"""Authorization helpers shared by the API routes.

The rules are in docs/CONTRACT.md section 6. Centralising them here keeps the
four-clause `if not kb.get("is_global") and kb.get("owner") != ... and role != ...`
condition from being re-typed (and mis-typed) in every endpoint.
"""

from typing import Any, Dict

from fastapi import HTTPException

from app.core import store


def _is_admin(user: Dict[str, Any]) -> bool:
    return user.get("role") == "admin"


def require_kb(kb_id: str) -> Dict[str, Any]:
    """Load a knowledge base or raise 404."""
    kb = store.get_kb(kb_id)
    if kb is None:
        raise HTTPException(status_code=404, detail="Knowledge base not found")
    return kb


def require_kb_read(kb_id: str, user: Dict[str, Any]) -> Dict[str, Any]:
    """Global KBs are readable by everyone; private ones by owner or admin."""
    kb = require_kb(kb_id)
    if kb["is_global"] or _is_admin(user) or kb["owner"] == user["username"]:
        return kb
    raise HTTPException(status_code=403, detail="Permission denied")


def require_kb_write(kb_id: str, user: Dict[str, Any]) -> Dict[str, Any]:
    """Only admins may write to global KBs; private ones by owner or admin."""
    kb = require_kb(kb_id)
    if kb["is_global"]:
        if _is_admin(user):
            return kb
        raise HTTPException(
            status_code=403,
            detail="Only admin can modify global knowledge bases",
        )
    if _is_admin(user) or kb["owner"] == user["username"]:
        return kb
    raise HTTPException(status_code=403, detail="Permission denied")


def require_session_access(session_id: str, user: Dict[str, Any]) -> Dict[str, Any]:
    """Load a session, or raise unless the caller owns it (or is admin).

    Without this check any logged-in user could read or delete anyone's
    conversation just by knowing a session id.
    """
    session = store.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found")
    if _is_admin(user) or session["owner"] == user["username"]:
        return session
    # Deliberately 404, not 403: a 403 confirms the session exists.
    raise HTTPException(status_code=404, detail="Session not found")


def require_document_access(doc_id: str, user: Dict[str, Any], write: bool = False):
    """Load a document and check permission on its knowledge base."""
    doc = store.get_document(doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found")
    if write:
        require_kb_write(doc["kb_id"], user)
    else:
        require_kb_read(doc["kb_id"], user)
    return doc


def require_admin(user: Dict[str, Any]) -> None:
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="Admin privileges required")
