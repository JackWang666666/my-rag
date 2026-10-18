package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code POST /api/feedback}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequest {
    private String sessionId;
    /** Zero-based index of the message within the session. */
    private int messageIndex;
    /** up | down */
    private String rating;
    private String comment;
}
