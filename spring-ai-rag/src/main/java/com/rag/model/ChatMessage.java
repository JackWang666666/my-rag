package com.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * One message in a chat history response.
 *
 * <p>{@code sources} is a list of citation objects ({@code text}, {@code score},
 * {@code source}, {@code documentId}), not a list of strings — the frontend
 * renders the score and the source name for each citation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    /** user | assistant */
    private String role;
    private String content;
    private List<Map<String, Object>> sources;
    /** Stored ISO-8601 UTC string, passed through as-is. */
    private String timestamp;
}
