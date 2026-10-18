package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Readable settings shape, fixed by docs/CONTRACT.md section 5.
 * Secrets (API keys, Neo4j credentials) are deliberately absent.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SettingsResponse {
    private String llmProvider;
    private String openaiApiBase;
    private String openaiModel;
    private String ollamaBaseUrl;
    private String ollamaModel;
    private String volcengineApiBase;
    private String volcengineModel;
    private String embeddingProvider;
    private String openaiEmbeddingModel;
    private String localEmbeddingModel;
    private String rerankerProvider;
    private String milvusHost;
    private int milvusPort;
    private String neo4jUri;
    private int chunkSize;
    private int chunkOverlap;
}
