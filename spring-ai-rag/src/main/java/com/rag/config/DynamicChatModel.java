package com.rag.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link ChatModel} whose delegate can be swapped at runtime.
 *
 * <p>Registered once in {@link ChatModelRegistry} at startup, so the map entry
 * itself never changes; only {@link #set} does, from {@link SettingsStore} when
 * a Settings PUT changes that provider's base URL, model name or key. A call
 * already in flight keeps using the delegate it read; only the next call sees
 * the new one.
 */
public class DynamicChatModel implements ChatModel {

    private final AtomicReference<ChatModel> delegate;

    public DynamicChatModel(ChatModel initial) {
        this.delegate = new AtomicReference<>(initial);
    }

    void set(ChatModel next) {
        delegate.set(next);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.get().call(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.get().getDefaultOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.get().stream(prompt);
    }
}
