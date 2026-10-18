package com.rag.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus vector storage.
 *
 * <p>The collection name, field names, types and index are fixed by
 * {@code docs/CONTRACT.md} section 2 — the Python backend reads and writes the
 * same collections, so none of it may drift. In particular the text field is
 * {@code text} (it used to be {@code content} here, which made every document
 * written by this backend invisible to the other one) and {@code metadata} is a
 * VARCHAR holding a JSON string rather than Milvus' JSON type, because that is
 * the one representation both SDKs agree on.
 *
 * <p>Chunk listing/search deliberately lives in {@link com.rag.store.MetadataStore}
 * instead of here: Milvus 2.4's {@code like} is prefix-only, so {@code %keyword%}
 * search against it silently returned nothing.
 */
@Service
@Slf4j
public class RetrievalService {

    /** Milvus VARCHAR max_length is in bytes; leave headroom for multi-byte text. */
    private static final int TEXT_MAX_CHARS = 65000;

    private final MilvusClientV2 milvusClient;
    private final Gson gson = new Gson();
    private final Set<String> loadedCollections = ConcurrentHashMap.newKeySet();

    public RetrievalService(MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    /** {@code kb_<32hex>} — must match the Python backend's collection naming. */
    public static String getCollectionName(String kbId) {
        return "kb_" + kbId;
    }

    public void createCollection(String kbId, int dimension) {
        String collectionName = getCollectionName(kbId);

        try {
            Boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());
            if (Boolean.TRUE.equals(exists)) {
                log.info("Collection '{}' already exists", collectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

            schema.addField(AddFieldReq.builder()
                    .fieldName("id")
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName("document_id")
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName("text")
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName("metadata")
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .build());

            schema.addField(AddFieldReq.builder()
                    .fieldName("embedding")
                    .dataType(DataType.FloatVector)
                    .dimension(dimension)
                    .build());

            List<IndexParam> indexParams = new ArrayList<>();
            indexParams.add(IndexParam.builder()
                    .fieldName("embedding")
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("nlist", 128))
                    .build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build());
            log.info("Created collection '{}' with dimension {}", collectionName, dimension);
        } catch (Exception e) {
            log.error("Failed to create collection '{}'", collectionName, e);
            throw new RuntimeException("Failed to create Milvus collection", e);
        }
    }

    /** A chunk ready for insertion; {@code id} is shared with the chunks table. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VectorChunk {
        private String id;
        private String documentId;
        private String text;
        private Map<String, Object> metadata;
        private float[] embedding;
    }

    public void insert(String kbId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String collectionName = getCollectionName(kbId);

        try {
            List<JsonObject> rows = new ArrayList<>();
            for (VectorChunk chunk : chunks) {
                String text = chunk.getText() == null ? "" : chunk.getText();
                if (text.length() > TEXT_MAX_CHARS) {
                    text = text.substring(0, TEXT_MAX_CHARS);
                }
                JsonObject row = new JsonObject();
                row.addProperty("id", chunk.getId());
                row.addProperty("document_id", chunk.getDocumentId());
                row.addProperty("text", text);
                row.addProperty("metadata",
                        gson.toJson(chunk.getMetadata() == null ? Map.of() : chunk.getMetadata()));
                row.add("embedding", gson.toJsonTree(chunk.getEmbedding()));
                rows.add(row);
            }

            milvusClient.insert(InsertReq.builder()
                    .collectionName(collectionName)
                    .data(rows)
                    .build());
            // Without a flush the freshly written chunks are not searchable, so an
            // upload appears to succeed and then returns no results.
            flush(collectionName);
            log.info("Inserted {} chunks into '{}'", rows.size(), collectionName);
        } catch (Exception e) {
            log.error("Failed to insert chunks into '{}'", collectionName, e);
            throw new RuntimeException("Failed to insert chunks into Milvus", e);
        }
    }

    public List<SearchResult> search(String kbId, float[] queryEmbedding, int topK) {
        String collectionName = getCollectionName(kbId);

        try {
            load(collectionName);

            SearchResp searchResp = milvusClient.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(new FloatVec(queryEmbedding)))
                    .annsField("embedding")
                    .metricType(IndexParam.MetricType.COSINE)
                    .searchParams(Map.of("nprobe", 16))
                    .topK(topK)
                    .outputFields(Arrays.asList("id", "text", "document_id", "metadata"))
                    .build());

            List<List<SearchResp.SearchResult>> results = searchResp.getSearchResults();
            List<SearchResult> searchResults = new ArrayList<>();
            if (results != null && !results.isEmpty()) {
                for (SearchResp.SearchResult result : results.get(0)) {
                    Map<String, Object> entity = result.getEntity();
                    Map<String, Object> metadata = parseMetadata(entity.get("metadata"));
                    // Let the caller attribute a hit to its document and KB even when
                    // several KBs were searched (global-KB merge).
                    metadata.put("document_id", String.valueOf(entity.get("document_id")));
                    metadata.put("kb_id", kbId);

                    searchResults.add(SearchResult.builder()
                            .id(asString(entity.get("id")))
                            .text(asString(entity.get("text")))
                            .documentId(asString(entity.get("document_id")))
                            .kbId(kbId)
                            .metadata(metadata)
                            // getScore() is a boxed Float and is null for some
                            // index/metric combinations.
                            .score(result.getScore() == null ? 0.0 : result.getScore().doubleValue())
                            .build());
                }
            }

            log.debug("Search returned {} results from '{}'", searchResults.size(), collectionName);
            return searchResults;
        } catch (Exception e) {
            log.warn("Search failed in collection '{}': {}", collectionName, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void deleteByDocument(String kbId, String documentId) {
        String collectionName = getCollectionName(kbId);
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("document_id == \"" + documentId.replace("\"", "") + "\"")
                    .build());
            flush(collectionName);
            log.info("Deleted chunks of document '{}' from '{}'", documentId, collectionName);
        } catch (Exception e) {
            log.error("Failed to delete chunks from '{}'", collectionName, e);
        }
    }

    public void dropCollection(String kbId) {
        String collectionName = getCollectionName(kbId);
        try {
            milvusClient.dropCollection(
                    DropCollectionReq.builder().collectionName(collectionName).build());
            loadedCollections.remove(collectionName);
            log.info("Dropped collection '{}'", collectionName);
        } catch (Exception e) {
            log.error("Failed to drop collection '{}'", collectionName, e);
        }
    }

    private void load(String collectionName) {
        if (loadedCollections.contains(collectionName)) {
            return;
        }
        try {
            milvusClient.loadCollection(
                    LoadCollectionReq.builder().collectionName(collectionName).build());
        } catch (Exception e) {
            // Already loaded is not an error worth failing the search over.
            log.debug("loadCollection('{}'): {}", collectionName, e.getMessage());
        }
        loadedCollections.add(collectionName);
    }

    private void flush(String collectionName) {
        try {
            milvusClient.flush(FlushReq.builder()
                    .collectionNames(Collections.singletonList(collectionName))
                    .build());
        } catch (Exception e) {
            log.warn("Flush of '{}' failed: {}", collectionName, e.getMessage());
        }
    }

    private Map<String, Object> parseMetadata(Object raw) {
        if (raw == null) {
            return new HashMap<>();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        try {
            Map<String, Object> parsed = gson.fromJson(String.valueOf(raw),
                    new TypeToken<Map<String, Object>>() {}.getType());
            return parsed != null ? parsed : new HashMap<>();
        } catch (Exception e) {
            log.debug("Unparseable chunk metadata: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String id;
        private String text;
        private String documentId;
        private String kbId;
        private Map<String, Object> metadata;
        /** Raw Milvus similarity; COSINE can be negative, so clamp before use. */
        private double score;
    }
}
