"""Settings management routes."""

from fastapi import APIRouter, Depends

from app.core.auth import get_current_user
from app.core.config import settings
from app.core.permissions import require_admin
from app.models.schemas import SettingsResponse, SettingsUpdate

router = APIRouter(prefix="/api/settings", tags=["settings"])

# Request field -> Settings attribute. Write-only fields (API keys, Neo4j
# credentials) are updatable but never returned.
FIELD_MAPPING = {
    "llm_provider": "LLM_PROVIDER",
    "openai_api_key": "OPENAI_API_KEY",
    "openai_api_base": "OPENAI_API_BASE",
    "openai_model": "OPENAI_MODEL",
    "ollama_base_url": "OLLAMA_BASE_URL",
    "ollama_model": "OLLAMA_MODEL",
    "volcengine_api_key": "VOLCENGINE_API_KEY",
    "volcengine_api_base": "VOLCENGINE_API_BASE",
    "volcengine_model": "VOLCENGINE_MODEL",
    "embedding_provider": "EMBEDDING_PROVIDER",
    "openai_embedding_model": "OPENAI_EMBEDDING_MODEL",
    "local_embedding_model": "LOCAL_EMBEDDING_MODEL",
    "reranker_provider": "RERANKER_PROVIDER",
    "milvus_host": "MILVUS_HOST",
    "milvus_port": "MILVUS_PORT",
    "neo4j_uri": "NEO4J_URI",
    "neo4j_user": "NEO4J_USER",
    "neo4j_password": "NEO4J_PASSWORD",
    "chunk_size": "CHUNK_SIZE",
    "chunk_overlap": "CHUNK_OVERLAP",
}


def _current() -> SettingsResponse:
    """The readable settings shape, shared with the Spring backend."""
    return SettingsResponse(
        llm_provider=settings.LLM_PROVIDER,
        openai_api_base=settings.OPENAI_API_BASE,
        openai_model=settings.OPENAI_MODEL,
        ollama_base_url=settings.OLLAMA_BASE_URL,
        ollama_model=settings.OLLAMA_MODEL,
        volcengine_api_base=settings.VOLCENGINE_API_BASE,
        volcengine_model=settings.VOLCENGINE_MODEL,
        embedding_provider=settings.EMBEDDING_PROVIDER,
        openai_embedding_model=settings.OPENAI_EMBEDDING_MODEL,
        local_embedding_model=settings.LOCAL_EMBEDDING_MODEL,
        reranker_provider=settings.RERANKER_PROVIDER,
        milvus_host=settings.MILVUS_HOST,
        milvus_port=settings.MILVUS_PORT,
        neo4j_uri=settings.NEO4J_URI,
        chunk_size=settings.CHUNK_SIZE,
        chunk_overlap=settings.CHUNK_OVERLAP,
    )


@router.get("", response_model=SettingsResponse)
async def get_settings(current_user: dict = Depends(get_current_user)):
    """Return current application settings (secrets are never included)."""
    return _current()


@router.put("", response_model=SettingsResponse)
async def update_settings(
    payload: SettingsUpdate,
    current_user: dict = Depends(get_current_user),
):
    """
    Update application settings at runtime.

    Only non-None fields in the payload are applied.
    Changes persist only for the lifetime of the running process.
    Admin only.
    """
    require_admin(current_user)

    for field, value in payload.model_dump(exclude_none=True, by_alias=False).items():
        attr_name = FIELD_MAPPING.get(field)
        if attr_name and hasattr(settings, attr_name):
            object.__setattr__(settings, attr_name, value)

    return _current()
