package com.rag.config;

import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

/**
 * Chat models by provider name, plus the configured default.
 *
 * <p>A dedicated type rather than a {@code Map<String, ChatModel>} bean: Spring
 * resolves an injected {@code Map<String, ChatModel>} as "every ChatModel bean
 * keyed by bean name", silently ignoring any map bean you declared. That is why
 * the runtime provider switch used to do nothing — the map arrived keyed
 * {@code openAiChatModel}/{@code ollamaChatModel} while the lookup used
 * {@code openai}/{@code ollama}.
 */
public record ChatModelRegistry(Map<String, ChatModel> byProvider, ChatModel defaultModel) {

    /** The model for {@code provider}, or the default when it is unknown or absent. */
    public ChatModel resolve(String provider) {
        if (provider == null || provider.isBlank()) {
            return defaultModel;
        }
        ChatModel model = byProvider.get(provider.trim().toLowerCase());
        return model != null ? model : defaultModel;
    }

    /**
     * Swaps the delegate behind {@code provider}'s entry, so the next
     * {@link #resolve} sees the new base URL, model name or key. A no-op if
     * {@code provider} was never offered (no key/URL at startup) — switching a
     * provider on from nothing still needs a restart, since there is no
     * {@link DynamicChatModel} wrapper there to swap.
     */
    public void replace(String provider, ChatModel next) {
        if (byProvider.get(provider) instanceof DynamicChatModel dynamic) {
            dynamic.set(next);
        }
    }
}
