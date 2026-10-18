package com.rag.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Component;

/**
 * Builds Ollama-backed Spring AI models on demand, the Ollama counterpart of
 * {@link OpenAiClientFactory}.
 *
 * <p>{@code OllamaApi} takes no key — only a base URL — so there is nothing
 * equivalent to {@code buildChatModel(baseUrl, apiKey, model)}'s key argument
 * here.
 */
@Component
public class OllamaClientFactory {

    public OllamaChatModel buildChatModel(String baseUrl, String model) {
        OllamaApi api = new OllamaApi(baseUrl);
        return new OllamaChatModel(api, new OllamaOptions().withModel(model));
    }

    public OllamaEmbeddingModel buildEmbeddingModel(String baseUrl, String model) {
        OllamaApi api = new OllamaApi(baseUrl);
        return new OllamaEmbeddingModel(api, new OllamaOptions().withModel(model));
    }
}
