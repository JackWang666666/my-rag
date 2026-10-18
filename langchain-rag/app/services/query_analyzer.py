"""Single-pass query analysis: routing + coreference resolution + rewriting.

This replaces the old router.py + query_rewriter.py pair. Both used to run as
separate non-streaming LLM calls before retrieval could start, so every question
paid two full round-trips before the first token. They need the same inputs and
produce complementary outputs, so they are one call now.

The output contract is shared with the Spring backend -- see docs/CONTRACT.md
section 8.
"""

import json
import logging
import time
from typing import Any, Dict, List, Optional, Tuple

from langchain_core.messages import HumanMessage

from app.services.llm import get_chat_model

logger = logging.getLogger(__name__)

VALID_ROUTES = ("rag", "graph", "hybrid", "direct")
VALID_INTENTS = ("factual", "analytical", "comparative", "procedural")
VALID_COMPLEXITY = ("simple", "moderate", "complex")

ANALYZE_PROMPT = """你是一个查询分析器。请一次性完成路由决策与查询优化。

知识库类型：{kb_type}
知识库名称：{kb_name}

对话历史：
{history}

用户问题：{question}

请完成以下工作：

1. **路由决策**：判断最佳处理策略
   - rag：需要检索知识库文档（事实性问题、需要引用原文）
   - graph：需要查询知识图谱（关系类问题，如"A和B什么关系"、"谁创建了X"）
   - hybrid：同时需要文档检索和图谱查询（复杂问题）
   - direct：可直接回答，无需检索（闲聊、通用知识、与知识库无关）
2. **意图识别**：factual / analytical / comparative / procedural
3. **指代消解**：结合历史，把"它"、"这个"、"上面提到的"替换为具体实体
4. **查询改写**：生成 2-3 个语义等价但表述不同的查询，用于多路召回
5. **关键词与图谱实体**：提取用于精确匹配的关键词，以及需要在图谱中查询的实体

请以JSON格式返回：
{{
    "route": "rag/graph/hybrid/direct",
    "confidence": 0.0-1.0,
    "reason": "简短理由",
    "intent": "factual/analytical/comparative/procedural",
    "resolvedQuery": "消解指代后的完整问题",
    "rewrittenQueries": ["改写查询1", "改写查询2"],
    "keywords": ["关键词1", "关键词2"],
    "graphEntities": ["实体1", "实体2"],
    "complexity": "simple/moderate/complex"
}}

只返回JSON，不要其他内容。"""

HYDE_PROMPT = """请根据以下问题，写一段假设性的答案文本（约100-200字），这段文本应该包含回答该问题所需的关键信息和术语。不需要准确，但要包含相关领域的专业词汇和概念。

问题：{question}

假设性答案："""

SUGGESTED_QUESTIONS_PROMPT = """根据下面的问答，生成2-4个用户接下来可能想问的后续问题。
要求：
- 与原问题和回答内容相关，但不是简单重复
- 每个问题简短（不超过30字），自然口语化
- 只返回JSON字符串数组，不要其他任何内容

问题：{question}

回答：{answer}

JSON数组："""


def _fallback(question: str, reason: str) -> Dict[str, Any]:
    """Safe default: plain RAG with the question unchanged."""
    return {
        "route": "rag",
        "confidence": 0.5,
        "reason": reason,
        "intent": "factual",
        "resolved_query": question,
        "rewritten_queries": [],
        "keywords": [],
        "graph_entities": [],
        "complexity": "simple",
    }


def _strip_code_fence(content: str) -> str:
    content = content.strip()
    if content.startswith("```"):
        # ```json\n{...}\n```
        parts = content.split("\n", 1)
        content = parts[1] if len(parts) > 1 else ""
        content = content.rsplit("```", 1)[0].strip()
    return content


def _one_of(value: Any, allowed: tuple, fallback: str) -> str:
    """Normalise an enum-ish field, or fall back.

    Trimmed and lower-cased first, because models answer "RAG" or "Graph" often
    enough to matter -- and because QueryAnalyzerService.oneOf does the same. An
    exact-match check here would route the identical model output differently
    depending on which backend answered.
    """
    if value is None:
        return fallback
    text = str(value).strip().lower()
    return text if text in allowed else fallback


def _as_float(value: Any, fallback: float) -> float:
    """Mirror of QueryAnalyzerService.asDouble."""
    if isinstance(value, bool):
        # float(True) is 1.0, but Double.parseDouble("true") throws, so the Java
        # side falls back here and this has to as well.
        return fallback
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _clamp01(value: float) -> float:
    if value != value:  # NaN; json.loads accepts the bare NaN literal
        return 0.0
    return max(0.0, min(1.0, value))


def _as_str_list(value: Any, limit: int = 5) -> List[str]:
    if not isinstance(value, list):
        return []
    out = []
    for item in value:
        if isinstance(item, str) and item.strip():
            out.append(item.strip())
        if len(out) >= limit:
            break
    return out


def _format_history(chat_history: Optional[List[dict]]) -> str:
    if not chat_history:
        return "无"
    parts = []
    for msg in chat_history[-6:]:
        role = "用户" if msg.get("role") == "user" else "助手"
        parts.append("%s: %s" % (role, (msg.get("content") or "")[:200]))
    return "\n".join(parts) if parts else "无"


def usage_from_message(message: Any) -> Dict[str, Optional[int]]:
    """Token counts from an AIMessage's usage_metadata, or all-null if absent.

    LangChain already normalises every provider's usage payload to this one
    shape (input_tokens/output_tokens/total_tokens) -- see llm.py's stream_usage
    comment for why it can still be missing on a streamed response.
    """
    usage = getattr(message, "usage_metadata", None) or {}
    return {
        "promptTokens": usage.get("input_tokens"),
        "completionTokens": usage.get("output_tokens"),
        "totalTokens": usage.get("total_tokens"),
    }


def call_record(
    step: str,
    model: Any,
    duration_ms: int,
    usage: Optional[Dict[str, Optional[int]]] = None,
    content_preview: Optional[str] = None,
) -> Dict[str, Any]:
    """One row of the 'thinking process' shown to the user -- docs/CONTRACT.md
    section 4, event `llmCall`."""
    usage = usage or {"promptTokens": None, "completionTokens": None, "totalTokens": None}
    return {
        "step": step,
        "model": getattr(model, "model_name", None),
        "promptTokens": usage.get("promptTokens"),
        "completionTokens": usage.get("completionTokens"),
        "totalTokens": usage.get("totalTokens"),
        "durationMs": duration_ms,
        "contentPreview": content_preview,
    }


async def analyze_query(
    question: str,
    kb_type: str = "document",
    kb_name: str = "",
    chat_history: Optional[List[dict]] = None,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Route and rewrite a query in one LLM call.

    Args:
        question: The user's raw question.
        kb_type: document / book / project.
        kb_name: The knowledge base's name (not the session title).
        chat_history: Recent messages as {role, content} dicts.

    Returns:
        (analysis, call_record): analysis has snake_case keys (route,
        confidence, reason, intent, resolved_query, rewritten_queries,
        keywords, graph_entities, complexity); call_record is the `llmCall`
        row for this step (docs/CONTRACT.md section 4).
    """
    llm = None
    t0 = time.monotonic()
    try:
        llm = get_chat_model(streaming=False)
        prompt = ANALYZE_PROMPT.format(
            kb_type=kb_type,
            kb_name=kb_name or "未命名",
            history=_format_history(chat_history),
            question=question,
        )

        response = await llm.ainvoke([HumanMessage(content=prompt)])
        duration_ms = int((time.monotonic() - t0) * 1000)
        raw = json.loads(_strip_code_fence(response.content))
        if not isinstance(raw, dict):
            record = call_record(
                "analyzing", llm, duration_ms, content_preview="查询分析返回格式异常，使用默认RAG"
            )
            return _fallback(question, "查询分析返回格式异常，使用默认RAG"), record
    except Exception as e:
        logger.warning("Query analysis failed, defaulting to RAG: %s", e)
        duration_ms = int((time.monotonic() - t0) * 1000)
        record = call_record("analyzing", llm, duration_ms, content_preview="查询分析失败，使用默认RAG")
        return _fallback(question, "查询分析失败，使用默认RAG"), record

    # The model may answer in either camelCase (as asked) or snake_case.
    def pick(*names):
        for name in names:
            if name in raw and raw[name] is not None:
                return raw[name]
        return None

    # rag is the safe default throughout: it retrieves, so the answer stays
    # grounded even when the analysis was no help.
    route = _one_of(pick("route"), VALID_ROUTES, "rag")
    intent = _one_of(pick("intent"), VALID_INTENTS, "factual")
    complexity = _one_of(pick("complexity"), VALID_COMPLEXITY, "simple")
    confidence = _clamp01(_as_float(pick("confidence"), 0.8))

    resolved = pick("resolvedQuery", "resolved_query")
    resolved_query = resolved.strip() if isinstance(resolved, str) and resolved.strip() else question

    reason = pick("reason")
    reason = reason if isinstance(reason, str) else ""

    analysis = {
        "route": route,
        "confidence": confidence,
        "reason": reason,
        "intent": intent,
        "resolved_query": resolved_query,
        "rewritten_queries": _as_str_list(pick("rewrittenQueries", "rewritten_queries"), 3),
        "keywords": _as_str_list(pick("keywords"), 8),
        "graph_entities": _as_str_list(pick("graphEntities", "graph_entities"), 8),
        "complexity": complexity,
    }
    record = call_record(
        "analyzing",
        llm,
        duration_ms,
        usage=usage_from_message(response),
        content_preview="route=%s, reason=%s" % (route, reason) if reason else "route=%s" % route,
    )
    return analysis, record


async def generate_hyde_document(question: str) -> str:
    """Generate a hypothetical answer to embed instead of the question (HyDE)."""
    try:
        llm = get_chat_model(streaming=False)
        response = await llm.ainvoke(
            [HumanMessage(content=HYDE_PROMPT.format(question=question))]
        )
        return response.content.strip()
    except Exception as e:
        logger.warning("HyDE generation failed: %s", e)
        return question


async def generate_suggested_questions(question: str, answer: str) -> Tuple[List[str], Dict[str, Any]]:
    """Generate 2-4 follow-up questions from the finished Q&A (see docs/CONTRACT.md section 4).

    Returns (questions, call_record): call_record is this step's `llmCall` row.
    """
    llm = None
    t0 = time.monotonic()
    try:
        llm = get_chat_model(streaming=False)
        response = await llm.ainvoke(
            [HumanMessage(content=SUGGESTED_QUESTIONS_PROMPT.format(question=question, answer=answer))]
        )
        duration_ms = int((time.monotonic() - t0) * 1000)
        raw = json.loads(_strip_code_fence(response.content))
        questions = _as_str_list(raw, 4)
        record = call_record(
            "suggesting",
            llm,
            duration_ms,
            usage=usage_from_message(response),
            content_preview=json.dumps(questions, ensure_ascii=False) if questions else None,
        )
        return questions, record
    except Exception as e:
        logger.warning("Suggested questions generation failed: %s", e)
        duration_ms = int((time.monotonic() - t0) * 1000)
        record = call_record("suggesting", llm, duration_ms, content_preview="生成猜你想问失败: %s" % e)
        return [], record
