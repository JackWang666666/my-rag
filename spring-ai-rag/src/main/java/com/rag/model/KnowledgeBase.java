package com.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A knowledge base as returned to the frontend (docs/CONTRACT.md section 5).
 *
 * <p>{@code createdAt} is the stored ISO-8601 UTC string ({@code ...Z}) passed
 * through verbatim rather than a {@code LocalDateTime}: the timestamp is written
 * by whichever backend created the row, and re-parsing it into a zone-less type
 * shifted it by the server's offset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {
    private String id;
    private String name;
    private String description;
    private String kbType;
    private int documentCount;
    private String createdAt;
    private String owner;
    // Without @JsonProperty, Jackson names the getter-derived property "global".
    @Builder.Default
    @JsonProperty("isGlobal")
    private boolean isGlobal = false;
}
