"""CRUD helpers over the SQLite metadata store.

All functions return plain dicts (or None) rather than Pydantic models so the
API layer stays free to shape responses. Row keys are the SQL column names.
"""

import json
import uuid
from typing import Any, Dict, List, Optional, Tuple

from app.core import db


def new_id() -> str:
    """32-char lowercase hex id.

    Shared with the Spring backend: the Milvus collection name is derived
    directly from it (`kb_<32hex>`), so it must not contain dashes.
    """
    return uuid.uuid4().hex


def _row(row) -> Optional[Dict[str, Any]]:
    return dict(row) if row is not None else None


def _rows(rows) -> List[Dict[str, Any]]:
    return [dict(r) for r in rows]


# --- Users ---------------------------------------------------------------


def get_user(username: str) -> Optional[Dict[str, Any]]:
    return _row(db.query_one("SELECT * FROM users WHERE username = ?", (username,)))


def create_user(username: str, hashed_password: str, role: str = "user") -> Dict[str, Any]:
    db.execute(
        "INSERT INTO users (username, hashed_password, role, created_at)"
        " VALUES (?, ?, ?, ?)",
        (username, hashed_password, role, db.now_iso()),
    )
    return {
        "username": username,
        "hashed_password": hashed_password,
        "role": role,
    }


def user_count() -> int:
    return db.query_one("SELECT COUNT(*) AS c FROM users")["c"]


# --- Knowledge bases -----------------------------------------------------


def create_kb(
    kb_id: str,
    name: str,
    description: str,
    kb_type: str,
    owner: str,
    is_global: bool,
    embedding_dim: int,
) -> Dict[str, Any]:
    db.execute(
        "INSERT INTO knowledge_bases"
        " (id, name, description, kb_type, owner, is_global, embedding_dim, created_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (kb_id, name, description, kb_type, owner, 1 if is_global else 0,
         embedding_dim, db.now_iso()),
    )
    return get_kb(kb_id)


def get_kb(kb_id: str) -> Optional[Dict[str, Any]]:
    return _row(db.query_one("SELECT * FROM knowledge_bases WHERE id = ?", (kb_id,)))


def list_kbs() -> List[Dict[str, Any]]:
    return _rows(db.query("SELECT * FROM knowledge_bases ORDER BY created_at DESC"))


def list_visible_kbs(username: str, role: str) -> List[Dict[str, Any]]:
    """Global KBs plus the user's own. Admins see everything."""
    if role == "admin":
        return list_kbs()
    return _rows(db.query(
        "SELECT * FROM knowledge_bases WHERE is_global = 1 OR owner = ?"
        " ORDER BY created_at DESC",
        (username,),
    ))


def list_owned_kbs(username: str) -> List[Dict[str, Any]]:
    """The caller's own private (non-global) knowledge bases, newest first.

    Deliberately not `list_visible_kbs`: that returns every KB for an admin,
    but "all my knowledge bases" means the caller's own private ones only --
    global-KB merging is a separate, orthogonal step in the chat pipeline.
    """
    return _rows(db.query(
        "SELECT * FROM knowledge_bases WHERE owner = ? AND is_global = 0"
        " ORDER BY created_at DESC",
        (username,),
    ))


def list_global_kb_ids(exclude: Optional[str] = None) -> List[str]:
    rows = db.query(
        "SELECT id FROM knowledge_bases WHERE is_global = 1 ORDER BY created_at DESC"
    )
    return [r["id"] for r in rows if r["id"] != exclude]


def delete_kb(kb_id: str) -> bool:
    # documents/chunks cascade via the foreign keys.
    return db.execute("DELETE FROM knowledge_bases WHERE id = ?", (kb_id,)) > 0


def kb_document_count(kb_id: str) -> int:
    return db.query_one(
        "SELECT COUNT(*) AS c FROM documents WHERE kb_id = ?", (kb_id,)
    )["c"]


def kb_document_counts() -> Dict[str, int]:
    """document_count for every KB in one query (avoids N+1 on the list view)."""
    rows = db.query("SELECT kb_id, COUNT(*) AS c FROM documents GROUP BY kb_id")
    return {r["kb_id"]: r["c"] for r in rows}


# --- Documents -----------------------------------------------------------


def create_document(
    doc_id: str,
    kb_id: str,
    filename: str,
    file_type: str,
    file_size: int = 0,
    status: str = "processing",
) -> Dict[str, Any]:
    db.execute(
        "INSERT INTO documents"
        " (id, kb_id, filename, file_type, file_size, chunk_count, status, error, created_at)"
        " VALUES (?, ?, ?, ?, ?, 0, ?, NULL, ?)",
        (doc_id, kb_id, filename, file_type, file_size, status, db.now_iso()),
    )
    return get_document(doc_id)


def get_document(doc_id: str) -> Optional[Dict[str, Any]]:
    return _row(db.query_one("SELECT * FROM documents WHERE id = ?", (doc_id,)))


def list_documents(kb_id: str) -> List[Dict[str, Any]]:
    return _rows(db.query(
        "SELECT * FROM documents WHERE kb_id = ? ORDER BY created_at DESC", (kb_id,)
    ))


def set_document_stage(doc_id: str, stage: str) -> None:
    db.execute("UPDATE documents SET stage = ? WHERE id = ?", (stage, doc_id))


def mark_document_completed(doc_id: str, chunk_count: int) -> None:
    db.execute(
        "UPDATE documents SET status = 'completed', chunk_count = ?, error = NULL"
        " WHERE id = ?",
        (chunk_count, doc_id),
    )


def mark_document_failed(doc_id: str, error: str) -> None:
    db.execute(
        "UPDATE documents SET status = 'failed', error = ? WHERE id = ?",
        (error[:1000], doc_id),
    )


def delete_document(doc_id: str) -> bool:
    db.execute("DELETE FROM chunks WHERE document_id = ?", (doc_id,))
    return db.execute("DELETE FROM documents WHERE id = ?", (doc_id,)) > 0


# --- Chunks --------------------------------------------------------------


def insert_chunks(kb_id: str, document_id: str, chunks: List[Dict[str, Any]]) -> None:
    """Insert the browsable copies of chunks written to Milvus.

    `chunks` items: {id, chunk_index, source, text, metadata(dict)}.
    """
    now = db.now_iso()
    db.execute_many(
        "INSERT OR REPLACE INTO chunks"
        " (id, kb_id, document_id, chunk_index, source, text, metadata, created_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                c["id"],
                kb_id,
                document_id,
                int(c.get("chunk_index", 0)),
                c.get("source", "") or "",
                c.get("text", ""),
                json.dumps(c.get("metadata") or {}, ensure_ascii=False),
                now,
            )
            for c in chunks
        ],
    )


def list_chunks(
    kb_id: str,
    page: int = 1,
    page_size: int = 20,
    search: Optional[str] = None,
) -> Tuple[List[Dict[str, Any]], int]:
    """Paginated chunk listing with real infix search.

    Milvus 2.4's `like` only supports prefix matching, so `%keyword%` search has
    to run here. Parameter binding also removes the expression-injection surface
    that string-concatenated Milvus filters had.
    """
    page = max(1, page)
    page_size = max(1, min(page_size, 200))
    offset = (page - 1) * page_size

    where = "kb_id = ?"
    params: List[Any] = [kb_id]
    if search:
        # ESCAPE so a literal % or _ in the query is not a wildcard.
        escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        where += " AND (text LIKE ? ESCAPE '\\' OR source LIKE ? ESCAPE '\\')"
        params.extend(["%" + escaped + "%", "%" + escaped + "%"])

    total = db.query_one(
        "SELECT COUNT(*) AS c FROM chunks WHERE " + where, tuple(params)
    )["c"]

    rows = db.query(
        "SELECT * FROM chunks WHERE " + where
        + " ORDER BY document_id, chunk_index LIMIT ? OFFSET ?",
        tuple(params + [page_size, offset]),
    )

    items = []
    for r in rows:
        try:
            metadata = json.loads(r["metadata"])
        except (ValueError, TypeError):
            metadata = {}
        items.append({
            "id": r["id"],
            "text": r["text"],
            "metadata": metadata,
            "document_id": r["document_id"],
            "source": r["source"],
            "chunk_index": r["chunk_index"],
        })
    return items, total


def count_chunks(kb_id: str) -> int:
    return db.query_one(
        "SELECT COUNT(*) AS c FROM chunks WHERE kb_id = ?", (kb_id,)
    )["c"]


# --- Sessions ------------------------------------------------------------


def create_session(
    session_id: str, title: str, kb_id: str, owner: str
) -> Dict[str, Any]:
    now = db.now_iso()
    db.execute(
        "INSERT INTO sessions (id, title, kb_id, owner, created_at, updated_at)"
        " VALUES (?, ?, ?, ?, ?, ?)",
        (session_id, title, kb_id, owner, now, now),
    )
    return get_session(session_id)


def get_session(session_id: str) -> Optional[Dict[str, Any]]:
    return _row(db.query_one("SELECT * FROM sessions WHERE id = ?", (session_id,)))


def list_sessions(
    owner: str, role: str, kb_id: Optional[str] = None
) -> List[Dict[str, Any]]:
    """Sessions visible to `owner`.

    Non-admins only ever see their own sessions -- without the owner filter any
    logged-in user could enumerate (and then read) every conversation.
    """
    where = []
    params: List[Any] = []
    if role != "admin":
        where.append("owner = ?")
        params.append(owner)
    if kb_id:
        where.append("kb_id = ?")
        params.append(kb_id)
    sql = "SELECT * FROM sessions"
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY updated_at DESC"
    return _rows(db.query(sql, tuple(params)))


def touch_session(session_id: str) -> None:
    db.execute(
        "UPDATE sessions SET updated_at = ? WHERE id = ?",
        (db.now_iso(), session_id),
    )


def rename_session(session_id: str, title: str) -> None:
    db.execute("UPDATE sessions SET title = ? WHERE id = ?", (title, session_id))


def delete_session(session_id: str) -> bool:
    db.execute("DELETE FROM feedback WHERE session_id = ?", (session_id,))
    return db.execute("DELETE FROM sessions WHERE id = ?", (session_id,)) > 0


def session_message_counts() -> Dict[str, int]:
    rows = db.query(
        "SELECT session_id, COUNT(*) AS c FROM messages GROUP BY session_id"
    )
    return {r["session_id"]: r["c"] for r in rows}


def session_message_count(session_id: str) -> int:
    return db.query_one(
        "SELECT COUNT(*) AS c FROM messages WHERE session_id = ?", (session_id,)
    )["c"]


# --- Messages ------------------------------------------------------------


def append_message(
    session_id: str,
    role: str,
    content: str,
    sources: Optional[List[Dict[str, Any]]] = None,
    confidence: Optional[float] = None,
    route: Optional[str] = None,
) -> int:
    """Append a message and return its index within the session."""
    next_index = db.query_one(
        "SELECT COALESCE(MAX(message_index) + 1, 0) AS n FROM messages"
        " WHERE session_id = ?",
        (session_id,),
    )["n"]
    db.execute(
        "INSERT INTO messages"
        " (id, session_id, message_index, role, content, sources, confidence, route, created_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            new_id(),
            session_id,
            next_index,
            role,
            content,
            json.dumps(sources, ensure_ascii=False) if sources else None,
            confidence,
            route,
            db.now_iso(),
        ),
    )
    touch_session(session_id)
    return next_index


def list_messages(session_id: str, limit: Optional[int] = None) -> List[Dict[str, Any]]:
    """Messages in a session, oldest first. `limit` keeps the most recent N."""
    if limit:
        rows = db.query(
            "SELECT * FROM messages WHERE session_id = ?"
            " ORDER BY message_index DESC LIMIT ?",
            (session_id, limit),
        )
        rows = list(reversed(rows))
    else:
        rows = db.query(
            "SELECT * FROM messages WHERE session_id = ? ORDER BY message_index",
            (session_id,),
        )

    out = []
    for r in rows:
        sources = None
        if r["sources"]:
            try:
                sources = json.loads(r["sources"])
            except (ValueError, TypeError):
                sources = None
        out.append({
            "message_index": r["message_index"],
            "role": r["role"],
            "content": r["content"],
            "sources": sources,
            "confidence": r["confidence"],
            "route": r["route"],
            "created_at": r["created_at"],
        })
    return out


# --- Feedback ------------------------------------------------------------


def upsert_feedback(
    session_id: str,
    message_index: int,
    owner: str,
    rating: str,
    comment: Optional[str] = None,
) -> None:
    """Record feedback; re-submitting for the same message overwrites it."""
    db.execute(
        "INSERT INTO feedback"
        " (id, session_id, message_index, owner, rating, comment, created_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(session_id, message_index) DO UPDATE SET"
        "   rating = excluded.rating,"
        "   comment = excluded.comment,"
        "   owner = excluded.owner,"
        "   created_at = excluded.created_at",
        (new_id(), session_id, message_index, owner, rating, comment, db.now_iso()),
    )


def feedback_stats(owner: Optional[str] = None) -> Dict[str, Any]:
    """Feedback totals. `owner=None` means site-wide (admin view)."""
    if owner:
        rows = db.query(
            "SELECT rating, COUNT(*) AS c FROM feedback WHERE owner = ? GROUP BY rating",
            (owner,),
        )
    else:
        rows = db.query("SELECT rating, COUNT(*) AS c FROM feedback GROUP BY rating")
    counts = {r["rating"]: r["c"] for r in rows}
    positive = counts.get("up", 0)
    negative = counts.get("down", 0)
    total = positive + negative
    return {
        "total": total,
        "positive": positive,
        "negative": negative,
        "satisfaction_rate": round(positive / total, 4) if total else 0.0,
    }
