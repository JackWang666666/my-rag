package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkItem {
    private String id;
    private String text;
    private Map<String, Object> metadata;
    private String documentId;
    private String source;
    /** Position within its document; the frontend orders by it. */
    private int chunkIndex;
}
