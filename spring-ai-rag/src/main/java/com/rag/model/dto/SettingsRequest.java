package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial settings update. Every field is nullable: only the ones present in the
 * request body are applied. The four write-only fields are accepted but never
 * echoed back (docs/CONTRACT.md section 5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingsRequest {
    private String llmProvider;
    private String openaiApiKey;
    private String openaiApiBase;
    private String openaiModel;
    private String ollamaBaseUrl;
    private String ollamaModel;
    private String volcengineApiKey;
    private String volcengineApiBase;
    private String volcengineModel;
    private String embeddingProvider;
    private String openaiEmbeddingModel;
    private String localEmbeddingModel;
    private String rerankerProvider;
    private String milvusHost;
    private Integer milvusPort;
    private String neo4jUri;
    private String neo4jUser;
    private String neo4jPassword;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
