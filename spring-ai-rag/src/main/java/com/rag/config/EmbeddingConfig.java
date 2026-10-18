package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding model wiring; the counterpart of {@link LlmConfig}, and subject to the
 * same {@link ProviderBeans} caveat: OpenAI's bean exists even with no API key, so
 * being present is not the same as being usable.
 */
@Configuration
@Slf4j
public class EmbeddingConfig {

    /**
     * The registry is built first and the {@code @Primary} default below just
     * reads from it, mirroring {@link LlmConfig} and for the same reason: both
     * beans must see the same {@link DynamicEmbeddingModel} instances, or
     * {@code SettingsStore.update()} would only ever reach one of them.
     */
    @Bean
    public EmbeddingModelRegistry embeddingModelRegistry(
            RagProperties props,
            Environment environment,
            ObjectProvider<OpenAiEmbeddingModel> openAi,
            ObjectProvider<OllamaEmbeddingModel> ollama) {
        Map<String, EmbeddingModel> available = available(environment, openAi, ollama);
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No embedding model is available. Set OPENAI_API_KEY (with "
                            + "OPENAI_API_BASE if you use a compatible gateway), or set "
                            + "EMBEDDING_PROVIDER=ollama with a reachable OLLAMA_BASE_URL.");
        }

        String requested = normalize(props.getEmbeddingProvider());
        EmbeddingModel defaultModel = available.get(requested);
        if (defaultModel == null) {
            Map.Entry<String, EmbeddingModel> fallback = available.entrySet().iterator().next();
            log.warn("EMBEDDING_PROVIDER={} is not available; falling back to {}", requested,
                    fallback.getKey());
            defaultModel = fallback.getValue();
        } else {
            log.info("Default embedding provider: {}", requested);
        }
        return new EmbeddingModelRegistry(Map.copyOf(available), defaultModel);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(EmbeddingModelRegistry registry) {
        return registry.defaultModel();
    }

    /**
     * Providers that are both present and configured, keyed by their settings
     * name. Each entry is wrapped in a {@link DynamicEmbeddingModel} so
     * {@link SettingsStore} can swap its delegate at runtime without the
     * registry's map ever changing identity.
     */
    private static Map<String, EmbeddingModel> available(
            Environment environment,
            ObjectProvider<OpenAiEmbeddingModel> openAi,
            ObjectProvider<OllamaEmbeddingModel> ollama) {
        Map<String, EmbeddingModel> models = new LinkedHashMap<>();
        OpenAiEmbeddingModel openAiModel = ProviderBeans.openAiConfigured(environment)
                ? ProviderBeans.ifUsable("openai", openAi)
                : null;
        if (openAiModel != null) {
            models.put("openai", new DynamicEmbeddingModel(openAiModel));
        }
        OllamaEmbeddingModel ollamaModel = ProviderBeans.ifUsable("ollama", ollama);
        if (ollamaModel != null) {
            models.put("ollama", new DynamicEmbeddingModel(ollamaModel));
        }
        return models;
    }

    private static String normalize(String provider) {
        return provider == null || provider.isBlank() ? "openai" : provider.trim().toLowerCase();
    }
}
