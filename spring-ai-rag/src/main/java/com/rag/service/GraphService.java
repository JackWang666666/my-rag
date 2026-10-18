package com.rag.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rag.model.GraphNode;
import com.rag.model.GraphRelation;
import com.rag.model.dto.GraphData;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Knowledge graph in Neo4j.
 *
 * <p>The graph shape is fixed by {@code docs/CONTRACT.md} section 3 so both
 * backends read the same graph:
 *
 * <pre>(:Entity {name, kb_id, type?})-[:RELATION {type, kb_id}]-&gt;(:Entity {name, kb_id})</pre>
 *
 * <p>Two things used to differ here and made this backend's graph invisible to
 * the other one: the KB property was {@code kbId} instead of {@code kb_id}, and
 * the predicate became the Neo4j <em>relationship type</em> (a new type per
 * predicate, e.g. {@code :IS_AUTHOR_OF}). The predicate now lives on a
 * {@code type} property of a single {@code :RELATION} type, which also keeps the
 * queries parameterisable instead of string-concatenated.
 */
@Service
@Slf4j
public class GraphService {

    /** Chars of a chunk handed to the extractor; mirrors the Python side. */
    private static final int EXTRACT_MAX_CHARS = 4000;

    /** Entities inspected when building chat context. */
    private static final int CONTEXT_MAX_ENTITIES = 5;

    private static final String EXTRACTION_PROMPT = """
            You are a knowledge graph extraction expert. Given the following text, extract all meaningful entity relationships as triples (subject, predicate, object).

            Rules:
            1. Extract clear, factual relationships only.
            2. Use concise entity names (proper nouns when possible).
            3. Use clear, specific predicates (e.g., "is_author_of", "located_in", "founded_by").
            4. Return a JSON array of objects with keys: "subject", "predicate", "object".
            5. If no meaningful relationships can be extracted, return an empty array [].

            Text:
            %s

            Return ONLY the JSON array, no other text:""";

    private final Driver neo4jDriver;
    private final LlmService llmService;
    private final Gson gson = new Gson();

    public GraphService(Driver neo4jDriver, LlmService llmService) {
        this.neo4jDriver = neo4jDriver;
        this.llmService = llmService;
    }

    /** A subject-predicate-object triple. */
    public record Triple(String subject, String predicate, String object) {
    }

    /** Ask the LLM for triples. Returns an empty list on any failure. */
    public List<Triple> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            String snippet = text.length() > EXTRACT_MAX_CHARS
                    ? text.substring(0, EXTRACT_MAX_CHARS)
                    : text;
            String response = llmService.call(String.format(EXTRACTION_PROMPT, snippet));
            return parseTriples(response);
        } catch (Exception e) {
            log.warn("Entity extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Triple> parseTriples(String response) {
        List<Map<String, Object>> raw;
        try {
            raw = gson.fromJson(stripCodeFence(response),
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
        } catch (Exception e) {
            log.warn("Entity extraction returned unparseable JSON: {}", e.getMessage());
            return List.of();
        }
        if (raw == null) {
            return List.of();
        }

        List<Triple> triples = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            if (item == null) {
                continue;
            }
            String subject = str(item.get("subject"));
            String predicate = str(item.get("predicate"));
            String object = str(item.get("object"));
            if (!subject.isEmpty() && !predicate.isEmpty() && !object.isEmpty()) {
                triples.add(new Triple(subject, predicate, object));
            }
        }
        return triples;
    }

    public void storeTriples(String kbId, List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        String cypher = """
                MERGE (s:Entity {name: $subject, kb_id: $kb_id})
                MERGE (o:Entity {name: $object, kb_id: $kb_id})
                MERGE (s)-[r:RELATION {type: $predicate, kb_id: $kb_id}]->(o)
                """;
        try (Session session = neo4jDriver.session()) {
            for (Triple triple : triples) {
                session.run(cypher, Values.parameters(
                        "subject", triple.subject(),
                        "object", triple.object(),
                        "predicate", triple.predicate(),
                        "kb_id", kbId));
            }
            log.info("Stored {} triples for KB '{}'", triples.size(), kbId);
        } catch (Exception e) {
            log.error("Failed to store graph data for KB '{}'", kbId, e);
        }
    }

    /** Extract from one chunk and store, in one call. Never throws. */
    public void extractAndStore(String kbId, String text) {
        storeTriples(kbId, extractEntities(text));
    }

    /** Every triple in a knowledge base, capped so the UI stays renderable. */
    public GraphData getFullGraph(String kbId) {
        String cypher = """
                MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
                RETURN s.name AS subject, r.type AS predicate, o.name AS object
                LIMIT 500
                """;
        return runGraphQuery(kbId, cypher, Values.parameters("kb_id", kbId));
    }

    /** Triples whose subject or object mentions {@code entity}. */
    public GraphData queryGraph(String kbId, String entity) {
        String cypher = """
                MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
                WHERE toLower(s.name) CONTAINS toLower($entity)
                   OR toLower(o.name) CONTAINS toLower($entity)
                RETURN s.name AS subject, r.type AS predicate, o.name AS object
                LIMIT 100
                """;
        return runGraphQuery(kbId, cypher, Values.parameters("kb_id", kbId, "entity", entity));
    }

    private GraphData runGraphQuery(String kbId, String cypher, org.neo4j.driver.Value params) {
        // LinkedHashMap so node order is stable between calls -- the frontend's
        // force layout jumps around otherwise.
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphRelation> edges = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Record record = result.next();
                String subject = record.get("subject").asString("");
                String predicate = record.get("predicate").asString("");
                String object = record.get("object").asString("");
                if (subject.isEmpty() || object.isEmpty()) {
                    continue;
                }

                nodes.computeIfAbsent(subject, name -> node(name, kbId));
                nodes.computeIfAbsent(object, name -> node(name, kbId));

                edges.add(GraphRelation.builder()
                        .source(subject)
                        .target(object)
                        .relation(predicate)
                        .build());
            }
        } catch (Exception e) {
            log.error("Graph query failed for KB '{}'", kbId, e);
        }

        return GraphData.builder()
                .nodes(new ArrayList<>(nodes.values()))
                .edges(edges)
                .build();
    }

    private static GraphNode node(String name, String kbId) {
        // The entity name is the id: node ids must be stable across queries so the
        // frontend can match edges to nodes, and Neo4j's internal id() is not.
        return GraphNode.builder()
                .id(name)
                .label(name)
                .properties(Map.of("kb_id", kbId))
                .build();
    }

    /** Remove a knowledge base's entire graph. Called when a KB is deleted. */
    public void deleteGraph(String kbId) {
        try (Session session = neo4jDriver.session()) {
            session.run("MATCH (n:Entity {kb_id: $kb_id}) DETACH DELETE n",
                    Values.parameters("kb_id", kbId));
            log.info("Deleted graph data for KB '{}'", kbId);
        } catch (Exception e) {
            log.error("Failed to delete graph data for KB '{}'", kbId, e);
        }
    }

    /**
     * Relationships around {@code entities}, formatted for the chat prompt.
     *
     * <p>Returns "" when nothing matches, so the caller can drop the section from
     * the prompt entirely.
     */
    public String getGraphContext(String kbId, List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        String cypher = """
                MATCH (s:Entity {kb_id: $kb_id})-[r:RELATION {kb_id: $kb_id}]->(o:Entity {kb_id: $kb_id})
                WHERE toLower(s.name) CONTAINS toLower($entity)
                   OR toLower(o.name) CONTAINS toLower($entity)
                RETURN s.name AS subject, r.type AS predicate, o.name AS object
                LIMIT 10
                """;

        // A set: the same triple often matches several of the query's entities.
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        try (Session session = neo4jDriver.session()) {
            int used = 0;
            for (String entity : entities) {
                if (entity == null || entity.isBlank() || used >= CONTEXT_MAX_ENTITIES) {
                    continue;
                }
                used++;
                Result result = session.run(cypher,
                        Values.parameters("kb_id", kbId, "entity", entity));
                while (result.hasNext()) {
                    Record record = result.next();
                    lines.add(record.get("subject").asString("")
                            + " --[" + record.get("predicate").asString("") + "]--> "
                            + record.get("object").asString(""));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build graph context for KB '{}': {}", kbId, e.getMessage());
            return "";
        }

        if (lines.isEmpty()) {
            return "";
        }
        return "Knowledge Graph Context:\n" + String.join("\n", lines);
    }

    /** Strip a ```json ... ``` fence if the model wrapped its answer in one. */
    static String stripCodeFence(String content) {
        String text = content == null ? "" : content.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        text = newline >= 0 ? text.substring(newline + 1) : "";
        int fence = text.lastIndexOf("```");
        return (fence >= 0 ? text.substring(0, fence) : text).trim();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
