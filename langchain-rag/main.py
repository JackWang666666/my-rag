"""FastAPI application entry point for the LangChain RAG backend."""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import auth, chat, document, feedback, graph, knowledge_base, settings
from app.core import db
from app.core.auth import init_admin
from app.core.config import settings as app_settings
from app.core.config import startup_warnings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="LangChain RAG Backend",
    description="RAG backend powered by LangChain, Milvus, and Neo4j",
    version="0.1.0",
)

# `allow_origins=["*"]` together with `allow_credentials=True` is rejected by
# browsers: the wildcard cannot be sent with credentialed requests. Configure
# the real origins via CORS_ORIGINS instead.
_origins = app_settings.cors_origin_list()
_wildcard = "*" in _origins

app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials=not _wildcard,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(knowledge_base.router)
app.include_router(document.router)
app.include_router(chat.router)
app.include_router(graph.router)
app.include_router(settings.router)
app.include_router(feedback.router)


@app.on_event("startup")
async def on_startup():
    """Create the metadata schema and the bootstrap admin account."""
    for warning in startup_warnings():
        logger.warning(warning)
    db.init_db()
    init_admin()
    logger.info("Metadata store: %s", db.db_path())


@app.get("/")
async def root():
    return {"message": "LangChain RAG Backend is running"}


@app.get("/health")
async def health():
    return {"status": "healthy"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
