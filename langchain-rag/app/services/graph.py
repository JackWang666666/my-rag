"""Knowledge graph service using Neo4j for entity extraction and storage."""

import json
import logging
from typing import Any, Dict, List, Tuple

from app.core.deps import get_neo4j_driver
from app.models.schemas import GraphData, GraphEdge, GraphNode
from app.services.llm import get_chat_model

logger = logging.getLogger(__name__)

EXTRACTION_PROMPT = """You are a knowledge graph extraction expert. Given the following text, extract all meaningful entity relationships as triples (subject, predicate, object).

Rules:
1. Extract clear, factual relationships only.
2. Use concise entity names (proper nouns when possible).
3. Use clear, specific predicates (e.g., "is_author_of", "located_in", "founded_by").
4. Return a JSON array of objects with keys: "subject", "predicate", "object".
5. If no meaningful relationships can be extracted, return an empty array [].

Text:
{text}

Return ONLY the JSON array, no other text:"""


async def extract_entities(text: str) -> List[Dict[str, str]]:
    """
    Use LLM to extract entity relationship triples from text.

    Args:
        text: The text to extract entities from.

    Returns:
        List of dicts with 'subject', 'predicate', 'object' keys.
    """
    try:
        llm = get_chat_model(streaming=False)
        prompt = EXTRACTION_PROMPT.format(text=text[:4000])  # Limit text length
        response = await llm.ainvoke(prompt)

        content = response.content.strip()
        # Try to parse JSON from the response
        if content.startswith("```"):
            # Remove markdown code fences
            content = content.split("\n", 1)[1].rsplit("```", 1)[0].strip()

        triples = json.loads(content)

        # Validate structure
        valid_triples = []
        for triple in triples:
            if all(key in triple for key in ("subject", "predicate", "object")):
                valid_triples.append({
                    "subject": str(triple["subject"]).strip(),
                    "predicate": str(triple["predicate"]).strip(),
                    "object": str(triple["object"]).strip(),
                })

        return valid_triples

    except (json.JSONDecodeError, Exception) as e:
        logger.warning(f"Failed to extract entities: {e}")
        return []


def store_triples(kb_id: str, triples: List[Dict[str, str]]) -> None:
    """
    Store extracted triples in Neo4j.

    Args:
        kb_id: Knowledge base identifier for namespacing.
        triples: List of dicts with 'subject', 'predicate', 'object' keys.
    """
    if not triples:
        return

    driver = get_neo4j_driver()

    with driver.session() as session:
        for triple in triples:
            subject = triple["subject"]
            predicate = triple["predicate"]
            obj = triple["object"]

            # Create or merge nodes and relationship
            query = """
            MERGE (s:Entity {name: $subject, kb_id: $kb_id})
            MERGE (o:Entity {name: $object, kb_id: $kb_id})
            MERGE (s)-[r:RELATION {type: $predicate, kb_id: $kb_id}]->(o)
            RETURN s, r, o
            """
            session.run(
                query,
                subject=subject,
                object=obj,
                predicate=predicate,
                kb_id=kb_id,
            )


def query_graph(kb_id: str, entity: str) -> GraphData:
    """
    Query the graph for relationships involving a specific entity.

    Args:
        kb_id: Knowledge base identifier.
        entity: Entity name to search for.

    Returns:
        GraphData with related nodes and edges.
    """
    driver = get_neo4j_driver()
    nodes_dict: Dict[str, GraphNode] = {}
    edges: List[GraphEdge] = []

    with driver.session() as session:
        # Find all relationships where the entity is subject or object
        query = """
        MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
        WHERE toLower(s.name) CONTAINS toLower($entity)
           OR toLower(o.name) CONTAINS toLower($entity)
        RETURN s.name AS subject, r.type AS predicate, o.name AS object
        LIMIT 100
        """
        result = session.run(query, kb_id=kb_id, entity=entity)

        for record in result:
            subject = record["subject"]
            predicate = record["predicate"]
            obj = record["object"]

            # Add nodes
            if subject not in nodes_dict:
                nodes_dict[subject] = GraphNode(
                    id=subject,
                    label=subject,
                    properties={"kb_id": kb_id},
                )
            if obj not in nodes_dict:
                nodes_dict[obj] = GraphNode(
                    id=obj,
                    label=obj,
                    properties={"kb_id": kb_id},
                )

            # Add edge
            edges.append(GraphEdge(
                source=subject,
                target=obj,
                relation=predicate,
            ))

    return GraphData(nodes=list(nodes_dict.values()), edges=edges)


def get_full_graph(kb_id: str) -> GraphData:
    """
    Get all nodes and edges for a knowledge base.

    Args:
        kb_id: Knowledge base identifier.

    Returns:
        GraphData with all nodes and edges.
    """
    driver = get_neo4j_driver()
    nodes_dict: Dict[str, GraphNode] = {}
    edges: List[GraphEdge] = []

    with driver.session() as session:
        query = """
        MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
        RETURN s.name AS subject, r.type AS predicate, o.name AS object
        LIMIT 500
        """
        result = session.run(query, kb_id=kb_id)

        for record in result:
            subject = record["subject"]
            predicate = record["predicate"]
            obj = record["object"]

            if subject not in nodes_dict:
                nodes_dict[subject] = GraphNode(
                    id=subject,
                    label=subject,
                    properties={"kb_id": kb_id},
                )
            if obj not in nodes_dict:
                nodes_dict[obj] = GraphNode(
                    id=obj,
                    label=obj,
                    properties={"kb_id": kb_id},
                )

            edges.append(GraphEdge(
                source=subject,
                target=obj,
                relation=predicate,
            ))

    return GraphData(nodes=list(nodes_dict.values()), edges=edges)


def delete_graph_data(kb_id: str) -> None:
    """
    Delete all graph data for a knowledge base.

    Args:
        kb_id: Knowledge base identifier.
    """
    driver = get_neo4j_driver()

    with driver.session() as session:
        # Delete all relationships and nodes for this KB
        query = """
        MATCH (n:Entity {kb_id: $kb_id})
        DETACH DELETE n
        """
        session.run(query, kb_id=kb_id)


def get_graph_context(kb_id: str, entities: List[str]) -> str:
    """
    Get graph context for a list of entities to augment RAG retrieval.

    Args:
        kb_id: Knowledge base identifier.
        entities: List of entity names to look up.

    Returns:
        Formatted string of graph relationships for context.
    """
    if not entities:
        return ""

    driver = get_neo4j_driver()
    context_parts: List[str] = []

    with driver.session() as session:
        for entity in entities[:5]:  # Limit to 5 entities
            query = """
            MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
            WHERE toLower(s.name) CONTAINS toLower($entity)
               OR toLower(o.name) CONTAINS toLower($entity)
            RETURN s.name AS subject, r.type AS predicate, o.name AS object
            LIMIT 10
            """
            result = session.run(query, kb_id=kb_id, entity=entity)

            for record in result:
                context_parts.append(
                    f"{record['subject']} --[{record['predicate']}]--> {record['object']}"
                )

    if context_parts:
        return "Knowledge Graph Context:\n" + "\n".join(set(context_parts))
    return ""
