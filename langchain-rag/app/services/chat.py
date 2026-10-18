"""RAG chat pipeline with streaming support.

Sessions and messages are persisted in SQLite (app.core.store), not in module
dicts, so history survives a restart and is visible to the Spring backend.
"""

import logging
import time
from typing import Any, AsyncGenerator, Dict, List, Optional, Tuple

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from app.core import db, store
from app.models.schemas import MessageItem, SessionResponse
from app.services.embedding import get_embeddings
from app.services.graph import get_graph_context
from app.services.llm import get_chat_model
from app.services.query_analyzer import analyze_query
from app.services.reranker import clamp01, rerank
from app.services.retrieval import search

logger = logging.getLogger(__name__)

# How many past messages to feed back into the model / query analyzer.
HISTORY_WINDOW = 6

# Length of the snippet echoed back in each citation. Must match the Java side's
# ChatService.SOURCE_SNIPPET_CHARS so both backends render identical sources.
SOURCE_SNIPPET_CHARS = 200

# --- System prompts by KB type ---

RAG_SYSTEM_PROMPT_DOCUMENT = """You are a helpful assistant. Answer questions based on the provided context documents.
If the context does not contain enough information to answer the question, say so honestly.
Always cite your sources when possible.

{graph_context}

Context from documents:
{context}
"""

RAG_SYSTEM_PROMPT_PROJECT = """You are an expert code analyst. You have deep knowledge of the project codebase loaded into this knowledge base.
When answering questions:
- Help users understand the project architecture and structure
- Explain how components interact
- Suggest where to add new features based on existing patterns
- Reference specific files and code sections from the context

{graph_context}

Context from the codebase:
{context}
"""

RAG_SYSTEM_PROMPT_BOOK = """You are a knowledgeable assistant with access to the full content of a book.
Answer questions about the book's content accurately, citing specific chapters or sections when possible.
If the context does not contain enough information to answer the question, say so honestly.

{graph_context}

Context from the book:
{context}
"""

DIRECT_SYSTEM_PROMPT = """You are a helpful assistant. Answer the user's question directly based on your general knowledge.
Be concise and helpful."""

# Keep the default for backward compatibility
RAG_SYSTEM_PROMPT = RAG_SYSTEM_PROMPT_DOCUMENT


def _get_system_prompt(kb_type: str) -> str:
    """Get the appropriate system prompt template based on knowledge base type."""
    if kb_type == "project":
        return RAG_SYSTEM_PROMPT_PROJECT
    elif kb_type == "book":
        return RAG_SYSTEM_PROMPT_BOOK
    else:
        return RAG_SYSTEM_PROMPT_DOCUMENT


def _calculate_confidence(
    reranked_results: List[Tuple[str, float, Dict[str, Any]]],
    route_confidence: float,
) -> float:
    """Confidence in the answer, from retrieval quality and route certainty.

    Scores are already normalised to [0, 1] by the reranker. The formula is
    duplicated in the Spring backend -- see docs/CONTRACT.md section 7.
    """
    if not reranked_results:
        return 0.2

    count_factor = min(len(reranked_results) / 5.0, 1.0)

    top_scores = [clamp01(score) for _, score, _ in reranked_results[:3]]
    avg_score = sum(top_scores) / len(top_scores)
    score_factor = min(avg_score / 0.8, 1.0)

    confidence = (
        count_factor * 0.3 + score_factor * 0.5 + clamp01(route_confidence) * 0.2
    )

    return round(clamp01(confidence), 2)


def _history_messages(session_id: str) -> List[Any]:
    """Recent history as LangChain messages."""
    messages = []
    for msg in store.list_messages(session_id, limit=HISTORY_WINDOW):
        if msg["role"] == "user":
            messages.append(HumanMessage(content=msg["content"]))
        else:
            messages.append(AIMessage(content=msg["content"]))
    return messages


def _history_dicts(session_id: str) -> List[Dict[str, str]]:
    """Recent history as {role, content} dicts for the query analyzer."""
    return [
        {"role": m["role"], "content": m["content"]}
        for m in store.list_messages(session_id, limit=HISTORY_WINDOW)
    ]


def _derive_title(question: str) -> str:
    title = question[:50].strip()
    if len(question) > 50:
        title += "..."
    return title or "New Session"


async def chat_stream(
    question: str,
    kb_id: str,
    session_id: Optional[str] = None,
    kb_type: str = "document",
    kb_name: str = "",
    username: str = "",
) -> AsyncGenerator[Dict[str, Any], None]:
    """
    Advanced RAG pipeline: analyze -> multi-query retrieval -> rerank -> stream LLM.

    Args:
        question: User's question.
        kb_id: Knowledge base ID to search.
        session_id: Existing session to continue, or None to start a new one.
        kb_type: Type of knowledge base (document, book, project).
        kb_name: Knowledge base name, passed to the query analyzer as context.
        username: Owner to record on a newly created session.

    Yields:
        Dicts with event type and data for SSE streaming.
    """
    # Create the session up front so the id we emit always exists in the DB.
    session = store.get_session(session_id) if session_id else None
    if session is None:
        session_id = store.new_id()
        session = store.create_session(
            session_id, _derive_title(question), kb_id, username
        )
    else:
        session_id = session["id"]
        if session["title"] in ("", "New Session"):
            new_title = _derive_title(question)
            store.rename_session(session_id, new_title)

    yield {"event": "session", "data": session_id}

    try:
        history = _history_dicts(session_id)

        # --- Step 1: Route + rewrite in a single LLM call ---
        t0 = time.monotonic()
        yield {"event": "stage", "data": {"stage": "analyzing", "status": "start"}}
        analysis = await analyze_query(
            question=question,
            kb_type=kb_type,
            kb_name=kb_name,
            chat_history=history,
        )
        route = analysis["route"]
        yield {
            "event": "stage",
            "data": {
                "stage": "analyzing",
                "status": "done",
                "elapsedMs": int((time.monotonic() - t0) * 1000),
            },
        }

        yield {
            "event": "metadata",
            "data": {
                "route": route,
                "confidence": analysis["confidence"],
                "reason": analysis["reason"],
                "intent": analysis["intent"],
                "complexity": analysis["complexity"],
            },
        }

        # --- Step 2: If direct route, skip retrieval ---
        if route == "direct":
            llm = get_chat_model(streaming=True)
            messages = [SystemMessage(content=DIRECT_SYSTEM_PROMPT)]
            messages.extend(_history_messages(session_id))
            messages.append(HumanMessage(content=question))

            yield {"event": "confidence", "data": 0.9}

            t0 = time.monotonic()
            yield {"event": "stage", "data": {"stage": "generating", "status": "start"}}
            full_response = ""
            async for chunk in llm.astream(messages):
                if chunk.content:
                    full_response += chunk.content
                    yield {"event": "chunk", "data": chunk.content}
            yield {
                "event": "stage",
                "data": {
                    "stage": "generating",
                    "status": "done",
                    "elapsedMs": int((time.monotonic() - t0) * 1000),
                },
            }

            store.append_message(session_id, "user", question)
            store.append_message(
                session_id, "assistant", full_response, confidence=0.9, route=route
            )

            yield {"event": "done", "data": ""}
            return

        # --- Step 3: Multi-query retrieval ---
        t0 = time.monotonic()
        yield {"event": "stage", "data": {"stage": "retrieving", "status": "start"}}
        resolved_query = analysis["resolved_query"]
        embeddings = get_embeddings()

        all_results: List[Tuple[str, float, Dict[str, Any]]] = []
        query_embedding = await embeddings.aembed_query(resolved_query)
        all_results.extend(search(kb_id, query_embedding, top_k=10))

        for rewritten in analysis["rewritten_queries"][:2]:
            if rewritten in (question, resolved_query):
                continue
            try:
                rw_embedding = await embeddings.aembed_query(rewritten)
                all_results.extend(search(kb_id, rw_embedding, top_k=5))
            except Exception as e:
                logger.warning("Rewritten-query retrieval failed: %s", e)

        # --- Global KB merge: also search global knowledge bases ---
        for gkb_id in store.list_global_kb_ids(exclude=kb_id)[:3]:
            try:
                all_results.extend(search(gkb_id, query_embedding, top_k=3))
            except Exception:
                pass  # Non-critical: skip unavailable global KBs

        # Deduplicate by text content
        seen_texts = set()
        unique_results: List[Tuple[str, float, Dict[str, Any]]] = []
        for text, score, meta in all_results:
            if text not in seen_texts:
                seen_texts.add(text)
                unique_results.append((text, score, meta))

        # --- Step 4: Reranker ---
        reranked = await rerank(question=resolved_query, documents=unique_results)
        yield {
            "event": "stage",
            "data": {
                "stage": "retrieving",
                "status": "done",
                "elapsedMs": int((time.monotonic() - t0) * 1000),
            },
        }

        # Build context from reranked results
        context_parts = []
        sources = []
        for text, score, metadata in reranked:
            context_parts.append(text)
            # camelCase, like every other response body: this dict goes out on the
            # `sources` SSE event and is also what /api/chat/history replays.
            sources.append({
                "text": text[:SOURCE_SNIPPET_CHARS],
                "score": round(score, 4),
                "source": metadata.get("source", "unknown"),
                "documentId": metadata.get("document_id", ""),
                "rerankScore": metadata.get("rerank_score"),
            })

        context = (
            "\n\n---\n\n".join(context_parts)
            if context_parts
            else "No relevant documents found."
        )

        # --- Step 5: Graph context augmentation ---
        graph_context = ""
        graph_terms = analysis["graph_entities"] or analysis["keywords"]
        if route in ("graph", "hybrid") and graph_terms:
            t0 = time.monotonic()
            yield {"event": "stage", "data": {"stage": "graph", "status": "start"}}
            try:
                graph_context = get_graph_context(kb_id, graph_terms)
            except Exception as e:
                logger.warning("Failed to get graph context: %s", e)
            yield {
                "event": "stage",
                "data": {
                    "stage": "graph",
                    "status": "done",
                    "elapsedMs": int((time.monotonic() - t0) * 1000),
                },
            }

        # --- Step 6: Calculate confidence ---
        confidence_score = _calculate_confidence(reranked, analysis["confidence"])
        yield {"event": "confidence", "data": confidence_score}

        # --- Step 7: Build prompt and stream LLM response ---
        system_prompt = _get_system_prompt(kb_type).format(
            context=context,
            graph_context=graph_context,
        )

        # The retrieved context belongs in a SystemMessage: putting it in the
        # HumanMessage makes the model treat it as part of the user's turn and
        # pollutes the conversation history.
        messages = [SystemMessage(content=system_prompt)]
        messages.extend(_history_messages(session_id))
        messages.append(HumanMessage(content=question))

        llm = get_chat_model(streaming=True)
        full_response = ""

        t0 = time.monotonic()
        yield {"event": "stage", "data": {"stage": "generating", "status": "start"}}
        async for chunk in llm.astream(messages):
            if chunk.content:
                full_response += chunk.content
                yield {"event": "chunk", "data": chunk.content}
        yield {
            "event": "stage",
            "data": {
                "stage": "generating",
                "status": "done",
                "elapsedMs": int((time.monotonic() - t0) * 1000),
            },
        }

        # --- Step 8: Append guidance if low confidence ---
        if confidence_score < 0.5:
            guidance = (
                "\n\n---\n"
                "我对这个回答的把握不太大。你可以试试换个方式提问，或者提供更多上下文。"
            )
            full_response += guidance
            yield {"event": "chunk", "data": guidance}

        if sources:
            yield {"event": "sources", "data": sources}

        # --- Step 9: Persist the exchange ---
        store.append_message(session_id, "user", question)
        store.append_message(
            session_id,
            "assistant",
            full_response,
            sources=sources or None,
            confidence=confidence_score,
            route=route,
        )

        yield {"event": "done", "data": ""}

    except Exception as e:
        logger.error("Chat stream error: %s", e)
        yield {"event": "error", "data": str(e)}


def _to_message_item(msg: Dict[str, Any]) -> MessageItem:
    return MessageItem(
        role=msg["role"],
        content=msg["content"],
        sources=msg["sources"],
        timestamp=db.parse_iso(msg["created_at"]),
    )


def get_history(session_id: str) -> List[MessageItem]:
    """Full chat history for a session, oldest first."""
    return [_to_message_item(m) for m in store.list_messages(session_id)]


def _to_session_response(row: Dict[str, Any], message_count: int) -> SessionResponse:
    return SessionResponse(
        session_id=row["id"],
        title=row["title"],
        knowledge_base_id=row["kb_id"],
        message_count=message_count,
        created_at=db.parse_iso(row["created_at"]),
        updated_at=db.parse_iso(row["updated_at"]),
    )


def list_sessions(
    owner: str,
    role: str = "user",
    knowledge_base_id: Optional[str] = None,
) -> List[SessionResponse]:
    """Sessions visible to `owner`, most recently updated first.

    Non-admins only see their own sessions.
    """
    rows = store.list_sessions(owner, role, knowledge_base_id)
    counts = store.session_message_counts()
    return [_to_session_response(r, counts.get(r["id"], 0)) for r in rows]


def create_session(
    knowledge_base_id: str, owner: str, title: str = "New Session"
) -> SessionResponse:
    """Explicitly create a new session owned by `owner`."""
    row = store.create_session(store.new_id(), title, knowledge_base_id, owner)
    return _to_session_response(row, 0)


def get_session(session_id: str) -> Optional[SessionResponse]:
    """Session detail by ID, or None if it does not exist."""
    row = store.get_session(session_id)
    if row is None:
        return None
    return _to_session_response(row, store.session_message_count(session_id))


def delete_session(session_id: str) -> bool:
    """Delete a session and its messages. Returns False if it did not exist."""
    return store.delete_session(session_id)
