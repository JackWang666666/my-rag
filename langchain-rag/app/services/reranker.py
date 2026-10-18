"""Reranker service for improving retrieval precision.

Three providers, selected with RERANKER_PROVIDER:

- ``local``: a sentence-transformers CrossEncoder. No API call, no token cost,
  and much better ordering than asking a chat model to grade 18 passages.
- ``llm``: chat-model scoring (the original behaviour). Costs one extra
  non-streaming call with the whole candidate set in the prompt.
- ``none``: keep the vector order.

Scores are normalised the same way in both backends -- see docs/CONTRACT.md
section 7 -- so the confidence numbers mean the same thing either side.
"""

import asyncio
import json
import logging
import threading
from typing import Any, Dict, List, Optional, Tuple

from langchain_core.messages import HumanMessage

from app.core.config import settings
from app.services.llm import get_chat_model

logger = logging.getLogger(__name__)

RERANK_PROMPT = """你是一个文档相关性评估专家。请评估以下每段文档与用户问题的相关程度。

用户问题：{question}

请对每段文档评分（0-10分）：
- 0-2分：完全无关
- 3-4分：有微弱关联但不能直接回答问题
- 5-6分：部分相关，包含一些有用信息
- 7-8分：高度相关，包含回答所需的关键信息
- 9-10分：完美匹配，直接回答了问题

文档列表：
{documents}

请以JSON数组格式返回每个文档的评分：
[{{"index": 0, "score": 8, "reason": "简短原因"}}, ...]

只返回JSON数组，不要其他内容。"""

_cross_encoder = None
_cross_encoder_lock = threading.Lock()
_cross_encoder_failed = False


def clamp01(value: float) -> float:
    """Clamp to [0, 1]. Milvus COSINE scores can be negative."""
    try:
        value = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, value))


def _combine(rerank_score: float, vector_score: float) -> float:
    """Blend a 0-10 rerank score with a normalised vector score."""
    return 0.7 * (rerank_score / 10.0) + 0.3 * clamp01(vector_score)


def _get_cross_encoder():
    """Lazily load the local cross-encoder (a few hundred MB, first call only)."""
    global _cross_encoder, _cross_encoder_failed
    if _cross_encoder is not None or _cross_encoder_failed:
        return _cross_encoder
    with _cross_encoder_lock:
        if _cross_encoder is None and not _cross_encoder_failed:
            try:
                from sentence_transformers import CrossEncoder

                logger.info("Loading local reranker %s", settings.RERANKER_MODEL)
                _cross_encoder = CrossEncoder(settings.RERANKER_MODEL)
            except Exception as e:
                _cross_encoder_failed = True
                logger.warning(
                    "Local reranker unavailable (%s); falling back to LLM reranking", e
                )
    return _cross_encoder


def _score_locally(question: str, texts: List[str]) -> List[float]:
    """Cross-encoder scores mapped onto the shared 0-10 scale."""
    model = _get_cross_encoder()
    if model is None:
        return []
    raw = model.predict([(question, t) for t in texts])
    scores = []
    for value in raw:
        value = float(value)
        # bge-reranker outputs a logit; sigmoid gives a 0-1 relevance probability.
        if value < 0.0 or value > 1.0:
            value = 1.0 / (1.0 + pow(2.718281828459045, -value))
        scores.append(value * 10.0)
    return scores


async def _rerank_local(
    question: str,
    documents: List[Tuple[str, float, Dict[str, Any]]],
    top_k: int,
    threshold: float,
) -> Optional[List[Tuple[str, float, Dict[str, Any]]]]:
    """Returns None if the local model could not be used."""
    texts = [d[0] for d in documents]
    try:
        # predict() is CPU/GPU bound and blocking: keep the event loop free.
        scores = await asyncio.get_event_loop().run_in_executor(
            None, _score_locally, question, texts
        )
    except Exception as e:
        logger.warning("Local reranking failed: %s", e)
        return None

    if not scores:
        return None

    results = []
    for i, rerank_score in enumerate(scores):
        if rerank_score < threshold:
            continue
        text, vector_score, metadata = documents[i]
        metadata = dict(metadata)
        metadata["rerank_score"] = round(rerank_score, 3)
        metadata["original_vector_score"] = vector_score
        results.append((text, _combine(rerank_score, vector_score), metadata))

    results.sort(key=lambda x: x[1], reverse=True)
    return results[:top_k]


async def _rerank_llm(
    question: str,
    documents: List[Tuple[str, float, Dict[str, Any]]],
    top_k: int,
    threshold: float,
) -> List[Tuple[str, float, Dict[str, Any]]]:
    try:
        llm = get_chat_model(streaming=False)

        doc_parts = []
        for i, (text, _score, metadata) in enumerate(documents):
            doc_parts.append(
                "[文档 %d] (来源: %s)\n%s"
                % (i, metadata.get("source", "unknown"), text[:500])
            )

        prompt = RERANK_PROMPT.format(
            question=question, documents="\n\n".join(doc_parts)
        )
        response = await llm.ainvoke([HumanMessage(content=prompt)])

        content = response.content.strip()
        if content.startswith("```"):
            parts = content.split("\n", 1)
            content = (parts[1] if len(parts) > 1 else "").rsplit("```", 1)[0].strip()

        scores = json.loads(content)

        results = []
        for item in scores:
            if not isinstance(item, dict):
                continue
            idx = item.get("index", -1)
            try:
                rerank_score = float(item.get("score", 0))
            except (TypeError, ValueError):
                continue
            if not (0 <= idx < len(documents)) or rerank_score < threshold:
                continue
            text, vector_score, metadata = documents[idx]
            metadata = dict(metadata)
            metadata["rerank_score"] = rerank_score
            metadata["original_vector_score"] = vector_score
            results.append((text, _combine(rerank_score, vector_score), metadata))

        results.sort(key=lambda x: x[1], reverse=True)
        return results[:top_k]

    except Exception as e:
        logger.warning("LLM reranking failed, returning vector order: %s", e)
        return _passthrough(documents, top_k)


def _passthrough(
    documents: List[Tuple[str, float, Dict[str, Any]]], top_k: int
) -> List[Tuple[str, float, Dict[str, Any]]]:
    """Vector order, with scores normalised to the shared [0, 1] scale."""
    results = [(text, clamp01(score), metadata) for text, score, metadata in documents]
    results.sort(key=lambda x: x[1], reverse=True)
    return results[:top_k]


async def rerank(
    question: str,
    documents: List[Tuple[str, float, Dict[str, Any]]],
    top_k: Optional[int] = None,
    threshold: Optional[float] = None,
) -> List[Tuple[str, float, Dict[str, Any]]]:
    """Rerank retrieval results.

    Args:
        question: The user's (resolved) question.
        documents: (text, vector_score, metadata) from initial retrieval.
        top_k: Max results to keep. Defaults to RERANK_TOP_K.
        threshold: Minimum rerank score on a 0-10 scale. Defaults to
            RERANK_THRESHOLD. Ignored when reranking is disabled.

    Returns:
        (text, score, metadata) sorted by score descending, score in [0, 1].
    """
    if not documents:
        return []

    if top_k is None:
        top_k = settings.RERANK_TOP_K
    if threshold is None:
        threshold = settings.RERANK_THRESHOLD

    provider = (settings.RERANKER_PROVIDER or "llm").lower()

    if provider == "none":
        return _passthrough(documents, top_k)

    # Nothing to reorder.
    if len(documents) <= 2:
        return _passthrough(documents, top_k)

    if provider == "local":
        result = await _rerank_local(question, documents, top_k, threshold)
        if result is not None:
            return result
        # Model missing or broken: LLM scoring is the next best thing.

    return await _rerank_llm(question, documents, top_k, threshold)
