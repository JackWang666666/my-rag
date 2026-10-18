package com.rag.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link EmbeddingModel} counterpart of {@link DynamicChatModel}: delegate
 * swappable at runtime, map entry in {@link EmbeddingModelRegistry} fixed.
 *
 * <p>{@code dimensions()} is not overridden: {@link EmbeddingModel}'s default
 * implementation probes the model with a one-word embedding call, which
 * reaches the current delegate through {@link #call}, so it already reflects
 * whichever model is live.
 */
public class DynamicEmbeddingModel implements EmbeddingModel {

    private final AtomicReference<EmbeddingModel> delegate;

    public DynamicEmbeddingModel(EmbeddingModel initial) {
        this.delegate = new AtomicReference<>(initial);
    }

    void set(EmbeddingModel next) {
        delegate.set(next);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.get().call(request);
    }

    @Override
    public List<Double> embed(Document document) {
        return delegate.get().embed(document);
    }
}
