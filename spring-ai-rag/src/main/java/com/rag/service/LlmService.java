package com.rag.service;

import com.rag.config.ChatModelRegistry;
import com.rag.config.SettingsStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Chat model access, with the provider selected at runtime from settings.
 *
 * <p>{@code ChatModel} already extends {@code StreamingChatModel} in Spring AI
 * 1.0.0-M1, so {@code stream()} is always available. The old
 * {@code instanceof StreamingChatModel} narrowing was not just redundant, it was
 * a compile error ("expression type cannot be a subtype of the pattern type").
 *
 * <p>The provider lookup goes through {@link ChatModelRegistry} because injecting
 * a {@code Map<String, ChatModel>} gets bean names, not provider names — see that
 * class for why the runtime switch used to be a silent no-op.
 */
@Service
@Slf4j
public class LlmService {

    private final ChatModelRegistry registry;
    private final SettingsStore settings;

    public LlmService(ChatModelRegistry registry, SettingsStore settings) {
        this.registry = registry;
        this.settings = settings;
    }

    public ChatModel getChatModel() {
        return registry.resolve(settings.current().getLlmProvider());
    }

    /** Stream a single-prompt completion as text deltas. */
    public Flux<String> stream(String prompt) {
        return stream(new Prompt(prompt));
    }

    /** Stream a multi-message completion (system + history + question). */
    public Flux<String> stream(List<Message> messages) {
        return stream(new Prompt(messages));
    }

    private Flux<String> stream(Prompt prompt) {
        return getChatModel().stream(prompt)
                .map(LlmService::contentOf)
                .filter(content -> !content.isEmpty());
    }

    /** Blocking completion. Used by the analyzer, reranker and graph extractor. */
    public String call(String prompt) {
        try {
            ChatResponse response = getChatModel().call(new Prompt(prompt));
            return contentOf(response);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Blocking completion over a full message list. Used for the first answer and
     * the bounded regenerate-once retry, where the caller needs the complete text
     * before deciding whether to keep it.
     */
    public String call(List<Message> messages) {
        try {
            ChatResponse response = getChatModel().call(new Prompt(messages));
            return contentOf(response);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private static String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getContent() == null) {
            return "";
        }
        return response.getResult().getOutput().getContent();
    }
}
