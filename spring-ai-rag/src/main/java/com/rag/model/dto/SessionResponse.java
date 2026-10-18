package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {
    private String sessionId;
    private String title;
    private String knowledgeBaseId;
    private int messageCount;
    /** Stored ISO-8601 UTC strings, passed through as-is. */
    private String createdAt;
    private String updatedAt;
}
