package com.rag.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixes up the two OpenAI settings that otherwise stop the application before it can
 * report a useful error: a missing API key, and a base URL with a {@code /v1} suffix.
 *
 * <h2>The key</h2>
 *
 * <p>Spring AI's {@code OpenAiAutoConfiguration} declares five eager singletons —
 * chat, embedding, image, audio transcription and audio speech — and every one of
 * them asserts a non-blank API key in its factory method. Spring instantiates eager
 * singletons during context refresh whether anything injects them or not, so with no
 * key the context aborted before it finished starting. That happened even with
 * {@code LLM_PROVIDER=ollama}: a purely local setup still demanded a key for a
 * provider it was never going to call.
 *
 * <p>Disabling the beans instead would be the tidier fix, and does not work:
 * {@code spring.ai.openai.chat.enabled}, {@code .embedding.enabled} and
 * {@code .image.enabled} exist, but the two audio beans are guarded only by
 * {@code @ConditionalOnMissingBean} and have no property to switch off. Handing out
 * a placeholder is the one lever that covers all five.
 *
 * <p>Nothing can reach OpenAI with the placeholder, because {@link LlmConfig} and
 * {@link EmbeddingConfig} ask {@link ProviderBeans#openAiConfigured(Environment)}
 * before offering {@code openai} at all — the placeholder makes the beans
 * constructible, it does not make the provider available. Injecting
 * {@code OpenAiChatModel} directly and bypassing the registry would earn a 401
 * rather than a clear error, which is why no code does that.
 *
 * <h2>The base URL</h2>
 *
 * <p>{@code OpenAiApi} hardcodes the paths {@code /v1/chat/completions} and
 * {@code /v1/embeddings} onto whatever base URL it is given, so the base URL must
 * <em>not</em> end in {@code /v1}. Every OpenAI-compatible gateway publishes its
 * address with the suffix included, because that is what the OpenAI SDKs want — and
 * it is the same value {@code OPENAI_API_BASE} takes on the LangChain side. Pasting
 * it in unchanged produced requests to {@code /v1/v1/chat/completions}, which come
 * back as a 404 from the gateway with nothing to suggest the URL was the problem.
 *
 * <p>So the suffix is stripped rather than rejected, and the substitution is logged:
 * silently rewriting configuration is only acceptable if the log says it happened.
 */
public class OpenAiEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /**
     * Deliberately not key-shaped: if this ever shows up in a request log or an
     * upstream error, it should be obvious what happened.
     */
    static final String PLACEHOLDER_API_KEY = "missing-openai-api-key";

    static final String API_KEY_PROPERTY = "spring.ai.openai.api-key";

    static final String BASE_URL_PROPERTY = "spring.ai.openai.base-url";

    /**
     * Every base URL Spring AI will read, shared one first.
     *
     * <p>The per-model overrides exist because company gateways commonly serve chat
     * and nothing else, so embeddings have to be pointed somewhere different. They
     * fall back to the shared value when blank, and they take the same suffix-free
     * form — so they need the same trimming.
     */
    static final List<String> BASE_URL_PROPERTIES = List.of(
            BASE_URL_PROPERTY,
            "spring.ai.openai.chat.base-url",
            "spring.ai.openai.embedding.base-url");

    static final String PROPERTY_SOURCE_NAME = "rag-openai-overrides";

    private final Log log;

    public OpenAiEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        // Logging is not initialised this early; a deferred log is replayed once it
        // is, so these lines land in the startup output in order.
        this.log = logFactory.getLog(OpenAiEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Map<String, Object> overrides = new LinkedHashMap<>();

        String apiKey = environment.getProperty(API_KEY_PROPERTY);
        if (apiKey == null || apiKey.isBlank()) {
            overrides.put(API_KEY_PROPERTY, PLACEHOLDER_API_KEY);
        }

        for (String property : BASE_URL_PROPERTIES) {
            String baseUrl = environment.getProperty(property);
            String trimmed = stripApiVersionSuffix(baseUrl);
            if (trimmed != null && !trimmed.equals(baseUrl)) {
                log.info("Using " + property + " '" + trimmed + "' instead of the configured '"
                        + baseUrl + "': Spring AI appends /v1 itself.");
                overrides.put(property, trimmed);
            }
        }

        if (!overrides.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
        }
    }

    /**
     * The given base URL without a trailing {@code /v1} or trailing slashes, or
     * {@code null} if there was nothing configured to trim.
     *
     * <p>Only a whole trailing path segment counts, so a gateway that genuinely
     * lives under {@code /openai/v1beta} or {@code /v1x} is left alone.
     */
    static String stripApiVersionSuffix(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String url = stripTrailingSlashes(baseUrl.trim());
        if (url.regionMatches(true, url.length() - 3, "/v1", 0, 3)) {
            url = stripTrailingSlashes(url.substring(0, url.length() - 3));
        }
        // A base URL of only "/v1" would leave nothing to talk to; keep the original
        // and let the transport complain about an address it can actually name.
        return url.isEmpty() ? baseUrl : url;
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * Last, so {@code ConfigDataEnvironmentPostProcessor} has already loaded
     * {@code application.yml} and the properties are actually readable. Unordered
     * post-processors already sort here, but the correctness of this one depends on
     * it, so it says so.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
