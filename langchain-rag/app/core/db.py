"""SQLite metadata persistence.

Everything that used to live in module-level dicts (users, knowledge bases,
documents, chunks, sessions, messages, feedback) lives here instead, so a
restart no longer orphans the Milvus collections and Neo4j nodes.

The schema is shared with the Spring backend -- see docs/CONTRACT.md. Do not
change it on one side only.
"""

import logging
import os
import sqlite3
import threading
from datetime import datetime, timezone

from app.core.config import settings

logger = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    username        TEXT PRIMARY KEY,
    hashed_password TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'user',
    created_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    kb_type       TEXT NOT NULL DEFAULT 'document',
    owner         TEXT NOT NULL,
    is_global     INTEGER NOT NULL DEFAULT 0,
    embedding_dim INTEGER NOT NULL DEFAULT 1536,
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS documents (
    id          TEXT PRIMARY KEY,
    kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    filename    TEXT NOT NULL,
    file_type   TEXT NOT NULL,
    file_size   INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    status      TEXT NOT NULL DEFAULT 'processing',
    stage       TEXT,
    error       TEXT,
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_documents_kb ON documents(kb_id);

CREATE TABLE IF NOT EXISTS chunks (
    id          TEXT PRIMARY KEY,
    kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    source      TEXT NOT NULL DEFAULT '',
    text        TEXT NOT NULL,
    metadata    TEXT NOT NULL DEFAULT '{}',
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunks_kb  ON chunks(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document_id);

CREATE TABLE IF NOT EXISTS sessions (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    kb_id      TEXT NOT NULL,
    owner      TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_owner ON sessions(owner);

CREATE TABLE IF NOT EXISTS messages (
    id            TEXT PRIMARY KEY,
    session_id    TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    message_index INTEGER NOT NULL,
    role          TEXT NOT NULL,
    content       TEXT NOT NULL,
    sources       TEXT,
    confidence    REAL,
    route         TEXT,
    created_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);

CREATE TABLE IF NOT EXISTS feedback (
    id            TEXT PRIMARY KEY,
    session_id    TEXT NOT NULL,
    message_index INTEGER NOT NULL,
    owner         TEXT NOT NULL,
    rating        TEXT NOT NULL,
    comment       TEXT,
    created_at    TEXT NOT NULL,
    UNIQUE(session_id, message_index)
);
"""

_local = threading.local()
_init_lock = threading.Lock()
_initialized = False


def db_path() -> str:
    """Absolute path of the SQLite file."""
    return os.path.abspath(settings.DB_PATH)


def _connect() -> sqlite3.Connection:
    path = db_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # check_same_thread=False is safe here because each thread gets its own
    # connection from threading.local().
    conn = sqlite3.connect(path, timeout=30.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    # WAL lets the Spring backend read while we write and vice versa.
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def _migrate(conn: sqlite3.Connection) -> None:
    """Add columns introduced after a table's `CREATE TABLE IF NOT EXISTS` first ran.

    `IF NOT EXISTS` only creates a table once; it never adds columns to one that
    already exists, so a column added here has to be backfilled explicitly on an
    existing database file.
    """
    columns = {row["name"] for row in conn.execute("PRAGMA table_info(documents)")}
    if "stage" not in columns:
        conn.execute("ALTER TABLE documents ADD COLUMN stage TEXT")


def get_conn() -> sqlite3.Connection:
    """Per-thread connection. Initializes the schema on first use."""
    global _initialized
    conn = getattr(_local, "conn", None)
    if conn is None:
        conn = _connect()
        _local.conn = conn
    if not _initialized:
        with _init_lock:
            if not _initialized:
                conn.executescript(SCHEMA)
                _migrate(conn)
                conn.commit()
                _initialized = True
                logger.info("SQLite metadata store ready at %s", db_path())
    return conn


def init_db() -> None:
    """Create the schema eagerly (called at app startup)."""
    get_conn()


def query(sql: str, params=()):
    """Run a SELECT and return all rows."""
    return get_conn().execute(sql, params).fetchall()


def query_one(sql: str, params=()):
    """Run a SELECT and return the first row or None."""
    return get_conn().execute(sql, params).fetchone()


def execute(sql: str, params=()) -> int:
    """Run a write statement and commit. Returns the affected row count."""
    conn = get_conn()
    cur = conn.execute(sql, params)
    conn.commit()
    return cur.rowcount


def execute_many(sql: str, seq_of_params) -> int:
    """Run a batched write statement and commit."""
    conn = get_conn()
    cur = conn.executemany(sql, seq_of_params)
    conn.commit()
    return cur.rowcount


# --- Timestamp helpers ---------------------------------------------------
# Stored as UTC ISO-8601 with a trailing Z so the Java side can parse them
# with Instant.parse().

def now_iso() -> str:
    """Current UTC time in the shared wire format."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def to_iso(dt: datetime) -> str:
    """Format a datetime in the shared wire format."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value) -> datetime:
    """Tolerant parser for timestamps written by either backend."""
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    if not value:
        return datetime.now(timezone.utc)
    text = str(value).strip().replace(" ", "T")
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        # Last resort: drop sub-second precision / trailing junk.
        try:
            dt = datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S")
        except ValueError:
            return datetime.now(timezone.utc)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
