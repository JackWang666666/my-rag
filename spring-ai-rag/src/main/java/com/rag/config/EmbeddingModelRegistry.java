package com.rag.config;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;

/**
 * Embedding models by provider name, plus the configured default.
 *
 * <p>Mirrors {@link ChatModelRegistry}, and exists for the same reason: an
 * injected {@code Map<String, EmbeddingModel>} would be filled with bean names,
 * not provider names.
 *
 * <p>Switching embedding provider at runtime only affects knowledge bases created
 * afterwards. An existing collection was built at the old model's dimension and
 * with its vector space, so re-embedding a query with a different model would
 * either fail on a dimension mismatch or return nonsense;
 * {@code RetrievalService} therefore keeps using the dimension recorded on the
 * knowledge base.
 */
public record EmbeddingModelRegistry(Map<String, EmbeddingModel> byProvider,
                                     EmbeddingModel defaultModel) {

    /** The model for {@code provider}, or the default when it is unknown or absent. */
    public EmbeddingModel resolve(String provider) {
        if (provider == null || provider.isBlank()) {
            return defaultModel;
        }
        EmbeddingModel model = byProvider.get(provider.trim().toLowerCase());
        return model != null ? model : defaultModel;
    }

    /**
     * Swaps the delegate behind {@code provider}'s entry, so the next
     * {@link #resolve} sees the new base URL or model name. A no-op if
     * {@code provider} was never offered (no key/URL at startup) — see
     * {@link ChatModelRegistry#replace} for why.
     */
    public void replace(String provider, EmbeddingModel next) {
        if (byProvider.get(provider) instanceof DynamicEmbeddingModel dynamic) {
            dynamic.set(next);
        }
    }
}
