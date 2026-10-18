package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat model wiring.
 *
 * <p>Both provider starters are on the classpath, so Spring AI auto-configures
 * {@code openAiChatModel} and {@code ollamaChatModel}. This class picks the
 * startup default from {@code LLM_PROVIDER} and publishes a
 * {@link ChatModelRegistry} so {@code LlmService} can switch provider at runtime
 * from the settings store.
 *
 * <p>{@link ObjectProvider} rather than {@code @Qualifier}: if a provider is
 * turned off (for example {@code spring.ai.openai.chat.enabled=false}) its bean
 * does not exist and a by-name qualifier would fail the whole context at startup.
 * A missing provider should just be absent from the registry.
 *
 * <p>Bean presence is necessary but not sufficient: OpenAI's bean exists even with
 * no API key configured, so {@link ProviderBeans#openAiConfigured} decides whether
 * {@code openai} is actually offered. Otherwise the registry would advertise a
 * provider whose every call returns 401.
 */
@Configuration
@Slf4j
public class LlmConfig {

    /**
     * The registry is built first and the {@code @Primary} default below just
     * reads from it, so {@code available()} runs exactly once and both beans
     * see the same {@link DynamicChatModel} instances — otherwise the default
     * bean and the registry would each wrap the startup model in their own
     * wrapper, and {@code SettingsStore.update()} would only ever reach one of
     * them.
     */
    @Bean
    public ChatModelRegistry chatModelRegistry(RagProperties props,
                                               Environment environment,
                                               ObjectProvider<OpenAiChatModel> openAi,
                                               ObjectProvider<OllamaChatModel> ollama) {
        Map<String, ChatModel> available = available(environment, openAi, ollama);
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No chat model is available. Set OPENAI_API_KEY (with "
                            + "OPENAI_API_BASE if you use a compatible gateway), or set "
                            + "LLM_PROVIDER=ollama with a reachable OLLAMA_BASE_URL.");
        }

        String requested = normalize(props.getLlmProvider());
        ChatModel defaultModel = available.get(requested);
        if (defaultModel == null) {
            Map.Entry<String, ChatModel> fallback = available.entrySet().iterator().next();
            log.warn("LLM_PROVIDER={} is not available; falling back to {}", requested,
                    fallback.getKey());
            defaultModel = fallback.getValue();
        } else {
            log.info("Default chat provider: {}", requested);
        }
        return new ChatModelRegistry(Map.copyOf(available), defaultModel);
    }

    @Bean
    @Primary
    public ChatModel chatModel(ChatModelRegistry registry) {
        return registry.defaultModel();
    }

    /**
     * Providers that are both present and configured, keyed by their settings
     * name. Each entry is wrapped in a {@link DynamicChatModel} so
     * {@link SettingsStore} can swap its delegate at runtime without the
     * registry's map ever changing identity.
     */
    private static Map<String, ChatModel> available(Environment environment,
                                                    ObjectProvider<OpenAiChatModel> openAi,
                                                    ObjectProvider<OllamaChatModel> ollama) {
        // Insertion-ordered so the fallback above is deterministic.
        Map<String, ChatModel> models = new LinkedHashMap<>();
        OpenAiChatModel openAiModel = ProviderBeans.openAiConfigured(environment)
                ? ProviderBeans.ifUsable("openai", openAi)
                : null;
        if (openAiModel != null) {
            models.put("openai", new DynamicChatModel(openAiModel));
        }
        OllamaChatModel ollamaModel = ProviderBeans.ifUsable("ollama", ollama);
        if (ollamaModel != null) {
            models.put("ollama", new DynamicChatModel(ollamaModel));
        }
        return models;
    }

    private static String normalize(String provider) {
        return provider == null || provider.isBlank() ? "openai" : provider.trim().toLowerCase();
    }
}
