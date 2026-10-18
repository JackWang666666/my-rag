"""Shared test fixtures.

Two things have to happen before any `app.*` module is imported, which is why they
are at module scope rather than in a fixture:

1. `app.core.config` builds its `Settings` singleton at import time, so DB_PATH and
   the security values have to be in the environment already. Otherwise the suite
   would open a developer's real `data/rag.db`.
2. The heavy optional dependencies (langchain, langchain-openai, pymilvus, neo4j,
   torch) are not needed by anything under test, but they sit in the import chain.
   Where one is missing we install a minimal stub, so `pytest` runs against a bare
   `pip install pytest pydantic-settings bcrypt python-jose fastapi`. When the
   real package *is* installed it is used unchanged — the stubs never shadow it.
"""

import importlib
import os
import sys
import tempfile
import types

import pytest

_TMP_DIR = tempfile.mkdtemp(prefix="rag-tests-")

# Environment variables win over the `.env` file in pydantic-settings, so this
# holds even for a developer who has langchain-rag/.env filled in.
os.environ["DB_PATH"] = os.path.join(_TMP_DIR, "rag.db")
# 32+ bytes, so startup_warnings() has nothing to say about its length.
os.environ["JWT_SECRET"] = "test-jwt-secret-that-is-long-enough-for-hs256"
os.environ["ADMIN_USERNAME"] = "admin"
os.environ["ADMIN_PASSWORD"] = "test-admin-password"
os.environ["CORS_ORIGINS"] = "http://localhost:3000"


def _stub_if_missing(name, build):
    """Install `build()` as module `name` only if the real one cannot be imported."""
    try:
        importlib.import_module(name)
        return False
    except ImportError:
        pass
    module = build()
    module.__name__ = name
    sys.modules[name] = module
    if "." in name:
        parent_name, _, attr = name.rpartition(".")
        parent = sys.modules.get(parent_name)
        if parent is not None:
            setattr(parent, attr, module)
    return True


def _build_langchain_core():
    package = types.ModuleType("langchain_core")
    package.__path__ = []  # marks it as a package so submodule imports resolve
    return package


def _build_langchain_core_messages():
    module = types.ModuleType("langchain_core.messages")

    class HumanMessage:
        def __init__(self, content=""):
            self.content = content

    module.HumanMessage = HumanMessage
    return module


def _build_app_services_llm():
    module = types.ModuleType("app.services.llm")

    def get_chat_model(provider=None, streaming=False):
        raise RuntimeError(
            "This is the test stub for app.services.llm. Tests must monkeypatch "
            "get_chat_model on the module under test instead of calling it."
        )

    module.get_chat_model = get_chat_model
    return module


_stub_if_missing("langchain_core", _build_langchain_core)
_stub_if_missing("langchain_core.messages", _build_langchain_core_messages)
_stub_if_missing("app.services.llm", _build_app_services_llm)


@pytest.fixture()
def fresh_db(tmp_path, monkeypatch):
    """A brand-new SQLite metadata store with the schema applied.

    `app.core.db` caches one connection per thread plus a module-level "schema
    already created" flag. Both have to be cleared, or every test after the first
    would silently keep writing to the first test's database.
    """
    from app.core import db
    from app.core.config import settings

    monkeypatch.setattr(settings, "DB_PATH", str(tmp_path / "rag.db"))
    _close_thread_conn(db)
    monkeypatch.setattr(db, "_initialized", False)

    db.init_db()
    yield db

    _close_thread_conn(db)


def _close_thread_conn(db):
    conn = getattr(db._local, "conn", None)
    if conn is not None:
        conn.close()
        del db._local.conn


@pytest.fixture()
def store(fresh_db):
    """The CRUD layer, bound to a fresh database."""
    from app.core import store as store_module

    return store_module
