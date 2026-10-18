"""Application configuration loaded from environment variables."""

import secrets

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from .env file."""

    # LLM Configuration
    LLM_PROVIDER: str = "openai"  # openai, ollama, volcengine
    OPENAI_API_KEY: str = ""
    OPENAI_API_BASE: str = "https://api.openai.com/v1"
    OPENAI_MODEL: str = "gpt-4o"

    # Ollama Configuration
    OLLAMA_BASE_URL: str = "http://localhost:11434"
    OLLAMA_MODEL: str = "llama3"

    # Volcengine Configuration
    VOLCENGINE_API_KEY: str = ""
    VOLCENGINE_API_BASE: str = ""
    VOLCENGINE_MODEL: str = ""

    # Embedding Configuration
    EMBEDDING_PROVIDER: str = "openai"  # openai, local
    OPENAI_EMBEDDING_MODEL: str = "text-embedding-3-small"
    LOCAL_EMBEDDING_MODEL: str = "BAAI/bge-m3"
    EMBEDDING_BATCH_SIZE: int = 64

    # Reranker Configuration
    RERANKER_PROVIDER: str = "llm"  # local, llm, none
    RERANKER_MODEL: str = "BAAI/bge-reranker-v2-m3"
    RERANK_TOP_K: int = 5
    RERANK_THRESHOLD: float = 3.0

    # Milvus Configuration
    MILVUS_HOST: str = "localhost"
    MILVUS_PORT: int = 19530

    # Neo4j Configuration
    NEO4J_URI: str = "bolt://localhost:7687"
    NEO4J_USER: str = "neo4j"
    NEO4J_PASSWORD: str = "neo4j123456"
    # Max chunks per ingest run to send through entity extraction
    GRAPH_MAX_CHUNKS: int = 40

    # Persistence: shared with the Spring backend, see docs/CONTRACT.md
    DB_PATH: str = "../data/rag.db"

    # Security
    JWT_SECRET: str = ""
    ACCESS_TOKEN_EXPIRE_HOURS: int = 72
    ADMIN_USERNAME: str = "admin"
    ADMIN_PASSWORD: str = ""
    CORS_ORIGINS: str = "http://localhost:3000"

    # Application Configuration
    UPLOAD_DIR: str = "./uploads"
    CHUNK_SIZE: int = 1000
    CHUNK_OVERLAP: int = 200
    MAX_UPLOAD_MB: int = 50

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
        "extra": "ignore",
    }

    def cors_origin_list(self):
        """CORS_ORIGINS as a list. `*` stays a single wildcard entry.

        An empty value gives no origins rather than falling back to the dev
        default: quietly re-allowing http://localhost:3000 in a deployment where
        someone blanked the variable is worse than allowing nothing.
        `startup_warnings()` points it out.
        """
        return [o.strip() for o in self.CORS_ORIGINS.split(",") if o.strip()]


settings = Settings()

# HS256 needs a 256-bit key. Both backends sign over the raw UTF-8 bytes of
# JWT_SECRET (no padding, no hash derivation) so tokens interchange, which means
# the shorter of the two constraints -- jjwt's -- applies to both.
MIN_JWT_SECRET_BYTES = 32

# A hardcoded fallback secret would let anyone forge tokens against a default
# deployment, so generate a per-process one instead and make the operator aware.
if not settings.JWT_SECRET:
    settings.JWT_SECRET = secrets.token_urlsafe(48)
    _JWT_SECRET_GENERATED = True
else:
    _JWT_SECRET_GENERATED = False

# Same reasoning for the bootstrap admin password.
if not settings.ADMIN_PASSWORD:
    settings.ADMIN_PASSWORD = secrets.token_urlsafe(12)
    _ADMIN_PASSWORD_GENERATED = True
else:
    _ADMIN_PASSWORD_GENERATED = False


def startup_warnings():
    """Warnings to log at startup about insecure/ephemeral defaults."""
    warnings = []
    if _JWT_SECRET_GENERATED:
        warnings.append(
            "JWT_SECRET is not set: generated a random one for this process. "
            "All tokens become invalid on restart. Set JWT_SECRET in .env "
            "(and use the same value as the Spring backend)."
        )
    if _ADMIN_PASSWORD_GENERATED:
        warnings.append(
            "ADMIN_PASSWORD is not set: generated the bootstrap admin password "
            "'%s'. Set ADMIN_PASSWORD in .env." % settings.ADMIN_PASSWORD
        )
    # Its own `if`, not an `elif` on the block above: the two conditions are
    # unrelated, and chaining them hid this warning whenever ADMIN_PASSWORD was
    # also unset -- which is the common case. Skipped when the secret was
    # generated here, since that one is always long enough.
    if (
        not _JWT_SECRET_GENERATED
        and len(settings.JWT_SECRET.encode("utf-8")) < MIN_JWT_SECRET_BYTES
    ):
        # python-jose signs a short secret happily, but jjwt on the Spring side
        # refuses keys under 256 bits, so a short secret means tokens issued here
        # simply cannot be verified there.
        warnings.append(
            "JWT_SECRET is shorter than %d characters: HS256 requires a 256-bit "
            "key and the Spring backend will refuse to start with it. Lengthen it "
            "in .env." % MIN_JWT_SECRET_BYTES
        )
    origins = settings.cors_origin_list()
    if "*" in origins:
        warnings.append(
            "CORS_ORIGINS is '*': credentials will not be accepted cross-origin. "
            "Set the actual frontend origin instead."
        )
    if not origins:
        warnings.append(
            "CORS_ORIGINS is empty: no browser origin is allowed, so the frontend "
            "cannot call this backend. Set it to the frontend's origin."
        )
    return warnings
