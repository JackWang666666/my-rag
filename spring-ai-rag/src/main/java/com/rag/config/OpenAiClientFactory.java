package com.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds OpenAI-backed Spring AI models on demand, for {@link SettingsStore} to
 * hand to {@link DynamicChatModel}/{@link DynamicEmbeddingModel} when a Settings
 * PUT changes the base URL, model name or key.
 *
 * <p>Cloning the auto-configured {@code RestClient.Builder}/{@code WebClient.Builder}
 * carries over {@link LlmHttpClientConfig}'s Jetty client and its five-minute
 * timeout, so a rebuilt model is not silently back on Spring's ten-second
 * default.
 */
@Component
public class OpenAiClientFactory {

    private final RestClient.Builder restClientBuilder;
    private final WebClient.Builder webClientBuilder;

    public OpenAiClientFactory(RestClient.Builder restClientBuilder,
                               WebClient.Builder webClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.webClientBuilder = webClientBuilder;
    }

    public OpenAiChatModel buildChatModel(String baseUrl, String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder.clone())
                .webClientBuilder(webClientBuilder.clone())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    public OpenAiEmbeddingModel buildEmbeddingModel(String baseUrl, String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder.clone())
                .webClientBuilder(webClientBuilder.clone())
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }
}
