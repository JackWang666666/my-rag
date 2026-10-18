"""Embedding factory for creating embedding model instances."""

from typing import Optional

from langchain_openai import OpenAIEmbeddings

from app.core.config import settings


def get_embeddings(provider: Optional[str] = None):
    """
    Create and return an embedding model based on the configured or specified provider.

    Args:
        provider: Override the configured embedding provider. One of: openai, local.

    Returns:
        A LangChain embeddings instance.
    """
    active_provider = provider or settings.EMBEDDING_PROVIDER

    if active_provider == "openai":
        return OpenAIEmbeddings(
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_API_BASE,
            model=settings.OPENAI_EMBEDDING_MODEL,
        )
    elif active_provider == "local":
        from langchain_community.embeddings import HuggingFaceEmbeddings

        return HuggingFaceEmbeddings(
            model_name=settings.LOCAL_EMBEDDING_MODEL,
            model_kwargs={"device": "cpu"},
            encode_kwargs={"normalize_embeddings": True},
        )
    else:
        raise ValueError(f"Unsupported embedding provider: {active_provider}")


def get_embedding_dimension(provider: Optional[str] = None) -> int:
    """
    Return the embedding dimension for the configured provider.

    Args:
        provider: Override the configured embedding provider.

    Returns:
        The dimension of the embedding vectors.
    """
    active_provider = provider or settings.EMBEDDING_PROVIDER

    if active_provider == "openai":
        model = settings.OPENAI_EMBEDDING_MODEL
        # Common OpenAI embedding dimensions
        dimensions = {
            "text-embedding-3-small": 1536,
            "text-embedding-3-large": 3072,
            "text-embedding-ada-002": 1536,
        }
        return dimensions.get(model, 1536)
    elif active_provider == "local":
        model = settings.LOCAL_EMBEDDING_MODEL
        # Common local model dimensions
        if "bge-m3" in model:
            return 1024
        elif "bge-large" in model:
            return 1024
        elif "bge-base" in model:
            return 768
        elif "bge-small" in model:
            return 512
        return 1024
    else:
        return 1536
