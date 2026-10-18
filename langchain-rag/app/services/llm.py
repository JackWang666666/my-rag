"""LLM factory for creating chat model instances."""

from typing import Optional

from langchain_openai import ChatOpenAI

from app.core.config import settings


def get_chat_model(provider: Optional[str] = None, streaming: bool = False):
    """
    Create and return a chat model based on the configured or specified provider.

    Args:
        provider: Override the configured LLM provider. One of: openai, ollama, volcengine.
        streaming: Whether to enable streaming mode.

    Returns:
        A LangChain chat model instance.
    """
    active_provider = provider or settings.LLM_PROVIDER

    if active_provider == "openai":
        return ChatOpenAI(
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_API_BASE,
            model=settings.OPENAI_MODEL,
            streaming=streaming,
            temperature=0.7,
            # Without this, astream() never gets a usage_metadata chunk -- the
            # request just never asks the server for one. No effect on ainvoke().
            stream_usage=True,
        )
    elif active_provider == "ollama":
        # Use ChatOpenAI with Ollama's OpenAI-compatible endpoint
        return ChatOpenAI(
            api_key="ollama",
            base_url=f"{settings.OLLAMA_BASE_URL}/v1",
            model=settings.OLLAMA_MODEL,
            streaming=streaming,
            temperature=0.7,
            # Without this, astream() never gets a usage_metadata chunk -- the
            # request just never asks the server for one. No effect on ainvoke().
            stream_usage=True,
        )
    elif active_provider == "volcengine":
        return ChatOpenAI(
            api_key=settings.VOLCENGINE_API_KEY,
            base_url=settings.VOLCENGINE_API_BASE,
            model=settings.VOLCENGINE_MODEL,
            streaming=streaming,
            temperature=0.7,
            # Without this, astream() never gets a usage_metadata chunk -- the
            # request just never asks the server for one. No effect on ainvoke().
            stream_usage=True,
        )
    else:
        raise ValueError(f"Unsupported LLM provider: {active_provider}")
