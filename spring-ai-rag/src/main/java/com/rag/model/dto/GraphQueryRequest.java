package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/graph/query}.
 *
 * <p>The field is {@code entity}, not {@code query}: see docs/CONTRACT.md
 * section 5. The frontend and the Python backend both send {@code entity}, so
 * the old name meant every graph query here searched for null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphQueryRequest {
    private String knowledgeBaseId;
    private String entity;
}
