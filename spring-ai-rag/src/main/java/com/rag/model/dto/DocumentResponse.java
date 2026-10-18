package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A document row as returned to the frontend (docs/CONTRACT.md section 5).
 *
 * <p>{@code fileSize}, {@code status} and {@code error} were missing here, so the
 * frontend's upload progress UI — which polls {@code GET /api/documents/{id}}
 * waiting for {@code status} to leave {@code processing} — never had anything to
 * read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {
    private String id;
    private String filename;
    private String fileType;
    private long fileSize;
    private int chunkCount;
    /** processing | completed | failed */
    private String status;
    /** parsing | embedding | graph; set while {@code status == "processing"}. */
    private String stage;
    /** Failure reason when {@code status == "failed"}, else null. */
    private String error;
    private String createdAt;
    private String knowledgeBaseId;
}
