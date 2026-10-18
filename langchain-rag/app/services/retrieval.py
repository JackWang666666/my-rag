"""Milvus vector store operations for document retrieval.

The collection layout is shared with the Spring backend -- see docs/CONTRACT.md
section 2. In particular `metadata` is a VARCHAR holding a JSON string (not the
Milvus JSON type) because that is the only representation both SDKs agree on.

Chunk browsing/searching is NOT here: it lives in app.core.store, backed by
SQLite. Milvus 2.4's `like` operator only supports prefix matching, so `%kw%`
search cannot be expressed as a Milvus filter at all.
"""

import json
import logging
from typing import Any, Dict, List, Tuple

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    utility,
)

from app.core.deps import get_milvus_connection

logger = logging.getLogger(__name__)

MAX_TEXT_LEN = 65000
_loaded_collections: set = set()


def get_collection_name(kb_id: str) -> str:
    """Milvus collection name for a knowledge base.

    KB ids are 32-char hex (uuid4().hex) so this needs no sanitising; the
    replace() is a safety net for ids created by older builds.
    """
    return "kb_" + kb_id.replace("-", "_")


# Backwards-compatible alias for internal callers.
_get_collection_name = get_collection_name


def create_collection(kb_id: str, dimension: int = 1536) -> None:
    """Create the collection for a knowledge base if it does not exist."""
    get_milvus_connection()

    collection_name = get_collection_name(kb_id)

    if utility.has_collection(collection_name):
        return

    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
        FieldSchema(name="document_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="text", dtype=DataType.VARCHAR, max_length=65535),
        # JSON string, not DataType.JSON: the Java SDK writes it as VARCHAR.
        FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=65535),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dimension),
    ]

    schema = CollectionSchema(fields=fields, description="Knowledge base: " + kb_id)
    collection = Collection(name=collection_name, schema=schema)

    index_params = {
        "index_type": "IVF_FLAT",
        "metric_type": "COSINE",
        "params": {"nlist": 128},
    }
    collection.create_index(field_name="embedding", index_params=index_params)


def _load(collection: Collection) -> None:
    """Load a collection into memory once per process."""
    if collection.name not in _loaded_collections:
        collection.load()
        _loaded_collections.add(collection.name)


def insert_documents(
    kb_id: str,
    chunk_ids: List[str],
    document_id: str,
    texts: List[str],
    embeddings: List[List[float]],
    metadatas: List[Dict[str, Any]],
) -> None:
    """Insert document chunks with embeddings into Milvus."""
    if not chunk_ids:
        return

    get_milvus_connection()

    collection = Collection(name=get_collection_name(kb_id))

    truncated_texts = [t[:MAX_TEXT_LEN] for t in texts]
    metadata_json = [
        json.dumps(m or {}, ensure_ascii=False)[:MAX_TEXT_LEN] for m in metadatas
    ]

    data = [
        chunk_ids,
        [document_id] * len(chunk_ids),
        truncated_texts,
        metadata_json,
        embeddings,
    ]

    collection.insert(data)
    # Without the flush, freshly uploaded chunks are invisible to search.
    collection.flush()


def _parse_metadata(raw: Any) -> Dict[str, Any]:
    """Metadata may be a JSON string (current) or a dict (legacy collections)."""
    if isinstance(raw, dict):
        return dict(raw)
    if isinstance(raw, str) and raw:
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                return parsed
        except ValueError:
            pass
    return {}


def search(
    kb_id: str,
    query_embedding: List[float],
    top_k: int = 5,
) -> List[Tuple[str, float, Dict[str, Any]]]:
    """Search for similar chunks. Returns (text, score, metadata) tuples."""
    get_milvus_connection()

    collection_name = get_collection_name(kb_id)

    if not utility.has_collection(collection_name):
        return []

    collection = Collection(name=collection_name)
    _load(collection)

    search_params = {
        "metric_type": "COSINE",
        "params": {"nprobe": 16},
    }

    results = collection.search(
        data=[query_embedding],
        anns_field="embedding",
        param=search_params,
        limit=top_k,
        output_fields=["text", "metadata", "document_id"],
    )

    search_results = []
    for hits in results:
        for hit in hits:
            metadata = _parse_metadata(hit.entity.get("metadata"))
            metadata["document_id"] = hit.entity.get("document_id", "")
            metadata["kb_id"] = kb_id
            search_results.append((hit.entity.get("text", ""), hit.score, metadata))

    return search_results


def delete_by_document(kb_id: str, document_id: str) -> None:
    """Delete all chunks belonging to a specific document."""
    get_milvus_connection()

    collection_name = get_collection_name(kb_id)

    if not utility.has_collection(collection_name):
        return

    collection = Collection(name=collection_name)
    _load(collection)
    collection.delete(expr='document_id == "%s"' % document_id.replace('"', ""))
    collection.flush()


def drop_collection(kb_id: str) -> None:
    """Drop an entire Milvus collection."""
    get_milvus_connection()

    collection_name = get_collection_name(kb_id)

    if utility.has_collection(collection_name):
        utility.drop_collection(collection_name)
    _loaded_collections.discard(collection_name)


def get_collection_count(kb_id: str) -> int:
    """Number of entities in a collection."""
    get_milvus_connection()

    collection_name = get_collection_name(kb_id)

    if not utility.has_collection(collection_name):
        return 0

    collection = Collection(name=collection_name)
    collection.flush()
    return collection.num_entities
