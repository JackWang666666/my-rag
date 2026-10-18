package com.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/knowledge-bases}.
 *
 * <p>{@code kbType} carries no field initializer on purpose: Jackson builds this
 * through the no-args constructor and Lombok's {@code @Builder.Default} strips
 * field initializers, so an omitted {@code kbType} would have arrived as null
 * despite looking defaulted. The controller applies the default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseRequest {
    private String name;
    private String description;
    /** document | book | project; blank or absent means {@code document}. */
    private String kbType;
    // Without @JsonProperty, Jackson expects "global" rather than "isGlobal".
    @JsonProperty("isGlobal")
    private boolean isGlobal;
}
