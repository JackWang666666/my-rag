package com.rag.model.dto;

import com.rag.model.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body of {@code GET /api/chat/history/{sessionId}}.
 *
 * <p>The contract is an object, not a bare array: the endpoint used to return
 * {@code ChatMessage[]} here while the frontend read {@code response.messages}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryResponse {
    private String sessionId;
    private List<ChatMessage> messages;
}
