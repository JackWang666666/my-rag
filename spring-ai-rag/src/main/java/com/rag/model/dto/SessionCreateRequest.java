package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/sessions}.
 *
 * <p>No field initializer on {@code title}: Lombok's {@code @Builder.Default}
 * moves it out of the field and Jackson deserializes through the no-args
 * constructor, so the "default" would silently be null anyway. The default lives
 * in {@code ChatService.createSession} instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCreateRequest {
    private String knowledgeBaseId;
    /** Blank or absent means "New Session". */
    private String title;
}
