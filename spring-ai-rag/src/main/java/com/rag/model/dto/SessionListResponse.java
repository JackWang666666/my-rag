package com.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Body of {@code GET /api/sessions} — {@code {sessions}}, not a bare array. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListResponse {
    private List<SessionResponse> sessions;
}
