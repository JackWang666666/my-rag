package com.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.model.dto.ChatHistoryResponse;
import com.rag.model.dto.ChatRequest;
import com.rag.model.dto.SessionCreateRequest;
import com.rag.model.dto.SessionListResponse;
import com.rag.model.dto.SessionResponse;
import com.rag.service.ChatService;
import com.rag.service.PermissionService;
import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import com.rag.web.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Chat streaming and session management (docs/CONTRACT.md sections 4 and 5).
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class ChatController {

    /** SSE idle timeout. Long, because a slow model plus reranking can take a while. */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatService chatService;
    private final PermissionService permissions;
    private final TaskExecutor chatExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatService chatService, PermissionService permissions,
                          @Qualifier("chatExecutor") TaskExecutor chatExecutor) {
        this.chatService = chatService;
        this.permissions = permissions;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw ApiException.badRequest("question must not be empty");
        }

        // Resolve the caller and check permissions on the request thread: the
        // SecurityContext is thread-local and is not visible from the executor.
        CurrentUser user = CurrentUser.get();
        MetadataStore.KbRow kb = permissions.requireKbRead(request.getKnowledgeBaseId(), user);

        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            MetadataStore.SessionRow session =
                    permissions.requireSessionAccess(request.getSessionId(), user);
            if (!session.kbId().equals(request.getKnowledgeBaseId())) {
                throw ApiException.badRequest("Session belongs to a different knowledge base");
            }
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.debug("SSE error: {}", e.getMessage()));

        ChatService.ChatContext ctx = new ChatService.ChatContext(
                request.getQuestion(), kb.id(), request.getSessionId(),
                kb.kbType(), kb.name(), user.username());

        chatExecutor.execute(() -> {
            try {
                chatService.streamChat(ctx, (event, data) -> emitter.send(
                        SseEmitter.event().name(event).data(serialize(data),
                                MediaType.TEXT_PLAIN)));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * Structured payloads travel as a JSON string in the {@code data:} line, which
     * is what the Python backend does and what the frontend parses.
     */
    private String serialize(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof CharSequence text) {
            return text.toString();
        }
        if (data instanceof Number || data instanceof Boolean) {
            return String.valueOf(data);
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Could not serialize SSE payload: {}", e.getMessage());
            return "";
        }
    }

    @GetMapping("/chat/history/{sessionId}")
    public ChatHistoryResponse getHistory(@PathVariable String sessionId) {
        permissions.requireSessionAccess(sessionId, CurrentUser.get());
        // An empty history is valid for a freshly created session, so this is a 200
        // with an empty list rather than a 404.
        return ChatHistoryResponse.builder()
                .sessionId(sessionId)
                .messages(chatService.getHistory(sessionId))
                .build();
    }

    @GetMapping("/sessions")
    public SessionListResponse listSessions(
            @RequestParam(name = "knowledgeBaseId", required = false) String knowledgeBaseId) {
        CurrentUser user = CurrentUser.get();
        return SessionListResponse.builder()
                .sessions(chatService.listSessions(user.username(), user.role(), knowledgeBaseId))
                .build();
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody SessionCreateRequest request) {
        CurrentUser user = CurrentUser.get();
        permissions.requireKbRead(request.getKnowledgeBaseId(), user);
        return chatService.createSession(request.getKnowledgeBaseId(), user.username(),
                request.getTitle());
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        permissions.requireSessionAccess(sessionId, CurrentUser.get());
        SessionResponse session = chatService.getSession(sessionId);
        if (session == null) {
            throw ApiException.notFound("Session not found");
        }
        return session;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        permissions.requireSessionAccess(sessionId, CurrentUser.get());
        chatService.deleteSession(sessionId);
        return Map.of("message", "Session deleted successfully");
    }
}
