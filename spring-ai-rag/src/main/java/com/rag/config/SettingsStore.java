package com.rag.config;

import com.rag.model.dto.SettingsRequest;
import com.rag.model.dto.SettingsResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime-mutable settings, seeded from configuration at startup.
 *
 * <p>The readable shape is fixed by {@code docs/CONTRACT.md} section 5 and is
 * identical to what the Python backend returns. Secrets are accepted on PUT but
 * never returned.
 *
 * <p>{@code llmProvider}, {@code embeddingProvider}, {@code rerankerProvider},
 * {@code chunkSize} and {@code chunkOverlap} take effect immediately because the
 * running services re-read them on every request. The OpenAI and Ollama base
 * URL, model name and (OpenAI) key also take effect immediately as of this
 * class: a change rebuilds that provider's {@code OpenAiChatModel}/
 * {@code OpenAiEmbeddingModel} (or Ollama equivalent) via
 * {@link OpenAiClientFactory}/{@link OllamaClientFactory} and swaps it into
 * {@link ChatModelRegistry}/{@link EmbeddingModelRegistry} through their
 * {@link DynamicChatModel}/{@link DynamicEmbeddingModel} wrapper — see those
 * classes. This only works for a provider that was already offered at startup
 * (a real key/URL was configured then); turning a provider on from nothing
 * still needs a restart, because there is no wrapper there yet to swap. Milvus
 * and Neo4j connection settings are still recorded and echoed back but not
 * applied — those clients are built at startup and are out of scope here.
 */
@Component
@Slf4j
public class SettingsStore {

    private final RagProperties props;
    private final ChatModelRegistry chatModelRegistry;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final OpenAiClientFactory openAiClientFactory;
    private final OllamaClientFactory ollamaClientFactory;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openaiApiBaseSeed;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKeySeed;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String openaiModelSeed;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrlSeed;

    @Value("${spring.ai.ollama.chat.options.model:qwen2:7b}")
    private String ollamaModelSeed;

    @Value("${VOLCENGINE_API_BASE:https://ark.cn-beijing.volces.com/api/v3}")
    private String volcengineApiBaseSeed;

    @Value("${VOLCENGINE_MODEL:doubao-pro-4k}")
    private String volcengineModelSeed;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}")
    private String openaiEmbeddingModelSeed;

    @Value("${LOCAL_EMBEDDING_MODEL:BAAI/bge-m3}")
    private String localEmbeddingModelSeed;

    @Value("${milvus.host:localhost}")
    private String milvusHostSeed;

    @Value("${milvus.port:19530}")
    private int milvusPortSeed;

    @Value("${spring.neo4j.uri:bolt://localhost:7687}")
    private String neo4jUriSeed;

    private volatile SettingsResponse current;

    /**
     * The live OpenAI key. Never part of {@link #current}: {@link SettingsResponse}
     * is the readable, echoed-back shape, and a key must not round-trip through it.
     */
    private volatile String openaiApiKey;

    public SettingsStore(RagProperties props, ChatModelRegistry chatModelRegistry,
                         EmbeddingModelRegistry embeddingModelRegistry,
                         OpenAiClientFactory openAiClientFactory,
                         OllamaClientFactory ollamaClientFactory) {
        this.props = props;
        this.chatModelRegistry = chatModelRegistry;
        this.embeddingModelRegistry = embeddingModelRegistry;
        this.openAiClientFactory = openAiClientFactory;
        this.ollamaClientFactory = ollamaClientFactory;
    }

    @PostConstruct
    void seed() {
        openaiApiKey = openaiApiKeySeed;
        current = SettingsResponse.builder()
                .llmProvider(props.getLlmProvider())
                .openaiApiBase(openaiApiBaseSeed)
                .openaiModel(openaiModelSeed)
                .ollamaBaseUrl(ollamaBaseUrlSeed)
                .ollamaModel(ollamaModelSeed)
                .volcengineApiBase(volcengineApiBaseSeed)
                .volcengineModel(volcengineModelSeed)
                .embeddingProvider(props.getEmbeddingProvider())
                .openaiEmbeddingModel(openaiEmbeddingModelSeed)
                .localEmbeddingModel(localEmbeddingModelSeed)
                .rerankerProvider(props.getRerankerProvider())
                .milvusHost(milvusHostSeed)
                .milvusPort(milvusPortSeed)
                .neo4jUri(neo4jUriSeed)
                .chunkSize(props.getChunkSize())
                .chunkOverlap(props.getChunkOverlap())
                .build();
    }

    public SettingsResponse current() {
        return current;
    }

    /** Apply the non-null fields of {@code request} and return the new state. */
    public synchronized SettingsResponse update(SettingsRequest request) {
        SettingsResponse.SettingsResponseBuilder builder = current.toBuilder();

        boolean openAiBaseOrKeyChanged = false;
        boolean openAiChatModelChanged = false;
        boolean openAiEmbeddingModelChanged = false;
        boolean ollamaChatChanged = false;

        if (request.getLlmProvider() != null) {
            builder.llmProvider(request.getLlmProvider());
            props.setLlmProvider(request.getLlmProvider());
        }
        if (request.getOpenaiApiBase() != null) {
            // Mirrors OpenAiEnvironmentPostProcessor's startup fix-up: Spring AI
            // appends /v1 itself, so a gateway address pasted in with it would
            // otherwise 404 against /v1/v1/....
            String normalized =
                    OpenAiEnvironmentPostProcessor.stripApiVersionSuffix(request.getOpenaiApiBase());
            builder.openaiApiBase(normalized != null ? normalized : request.getOpenaiApiBase());
            openAiBaseOrKeyChanged = true;
        }
        if (request.getOpenaiModel() != null) {
            builder.openaiModel(request.getOpenaiModel());
            openAiChatModelChanged = true;
        }
        if (request.getOpenaiApiKey() != null && !request.getOpenaiApiKey().isBlank()) {
            openaiApiKey = request.getOpenaiApiKey();
            openAiBaseOrKeyChanged = true;
        }
        if (request.getOllamaBaseUrl() != null) {
            builder.ollamaBaseUrl(request.getOllamaBaseUrl());
            ollamaChatChanged = true;
        }
        if (request.getOllamaModel() != null) {
            builder.ollamaModel(request.getOllamaModel());
            ollamaChatChanged = true;
        }
        if (request.getVolcengineApiBase() != null) {
            builder.volcengineApiBase(request.getVolcengineApiBase());
        }
        if (request.getVolcengineModel() != null) {
            builder.volcengineModel(request.getVolcengineModel());
        }
        if (request.getEmbeddingProvider() != null) {
            builder.embeddingProvider(request.getEmbeddingProvider());
            props.setEmbeddingProvider(request.getEmbeddingProvider());
        }
        if (request.getOpenaiEmbeddingModel() != null) {
            builder.openaiEmbeddingModel(request.getOpenaiEmbeddingModel());
            openAiEmbeddingModelChanged = true;
        }
        if (request.getLocalEmbeddingModel() != null) {
            builder.localEmbeddingModel(request.getLocalEmbeddingModel());
        }
        if (request.getRerankerProvider() != null) {
            builder.rerankerProvider(request.getRerankerProvider());
            props.setRerankerProvider(request.getRerankerProvider());
        }
        if (request.getMilvusHost() != null) {
            builder.milvusHost(request.getMilvusHost());
        }
        if (request.getMilvusPort() != null) {
            builder.milvusPort(request.getMilvusPort());
        }
        if (request.getNeo4jUri() != null) {
            builder.neo4jUri(request.getNeo4jUri());
        }
        if (request.getChunkSize() != null) {
            builder.chunkSize(request.getChunkSize());
            props.setChunkSize(request.getChunkSize());
        }
        if (request.getChunkOverlap() != null) {
            builder.chunkOverlap(request.getChunkOverlap());
            props.setChunkOverlap(request.getChunkOverlap());
        }

        current = builder.build();

        if (openAiBaseOrKeyChanged || openAiChatModelChanged) {
            chatModelRegistry.replace("openai", openAiClientFactory.buildChatModel(
                    current.getOpenaiApiBase(), openaiApiKey, current.getOpenaiModel()));
            log.info("Rebuilt the OpenAI chat client (base={}, model={})",
                    current.getOpenaiApiBase(), current.getOpenaiModel());
        }
        if (openAiBaseOrKeyChanged || openAiEmbeddingModelChanged) {
            embeddingModelRegistry.replace("openai", openAiClientFactory.buildEmbeddingModel(
                    current.getOpenaiApiBase(), openaiApiKey, current.getOpenaiEmbeddingModel()));
            log.info("Rebuilt the OpenAI embedding client (base={}, model={})",
                    current.getOpenaiApiBase(), current.getOpenaiEmbeddingModel());
        }
        if (ollamaChatChanged) {
            chatModelRegistry.replace("ollama", ollamaClientFactory.buildChatModel(
                    current.getOllamaBaseUrl(), current.getOllamaModel()));
            log.info("Rebuilt the Ollama chat client (base={}, model={})",
                    current.getOllamaBaseUrl(), current.getOllamaModel());
        }

        log.info("Settings updated: llmProvider={}, embeddingProvider={}, rerankerProvider={}",
                current.getLlmProvider(), current.getEmbeddingProvider(), current.getRerankerProvider());
        return current;
    }
}
