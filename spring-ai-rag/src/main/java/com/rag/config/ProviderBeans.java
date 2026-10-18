package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;

/**
 * Decides which model providers {@link LlmConfig} and {@link EmbeddingConfig} may
 * offer.
 *
 * <p>A provider bean existing is not the same as a provider being usable. OpenAI's
 * beans are always constructed — see {@link OpenAiEnvironmentPostProcessor} for
 * why they have to be, key or no key — so bean presence says nothing about whether
 * a key was configured. {@link #openAiConfigured(Environment)} is the actual gate.
 */
@Slf4j
final class ProviderBeans {

    private ProviderBeans() {
    }

    /**
     * Whether a real OpenAI key was configured, as opposed to the placeholder
     * {@link OpenAiEnvironmentPostProcessor} supplies so the context can start.
     *
     * <p>Any base URL works: an OpenAI-compatible gateway (a company proxy, Azure
     * OpenAI, vLLM, Ollama's own compatibility endpoint) is configured exactly like
     * OpenAI itself, and its key is just as real.
     */
    static boolean openAiConfigured(Environment environment) {
        String apiKey = environment.getProperty(OpenAiEnvironmentPostProcessor.API_KEY_PROPERTY);
        return apiKey != null
                && !apiKey.isBlank()
                && !OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY.equals(apiKey.trim());
    }

    /**
     * Looks up an optional provider bean, treating "cannot be built" as "not
     * present".
     *
     * <p>{@link ObjectProvider#getIfAvailable()} tolerates a missing bean
     * <em>definition</em> but not a definition whose instantiation throws, so a
     * provider with incomplete configuration would abort the whole context instead
     * of simply being absent from the registry. This does not help with the OpenAI
     * beans — Spring instantiates those eagerly, long before anything here looks
     * them up — but it keeps that failure mode from reappearing for whichever
     * provider is added next.
     *
     * @param name     provider name as used in settings, for the log line
     * @param provider the injected {@code ObjectProvider} for that model bean
     * @return the bean, or {@code null} if it is absent or unconstructible
     */
    static <T> T ifUsable(String name, ObjectProvider<T> provider) {
        try {
            return provider.getIfAvailable();
        } catch (BeansException e) {
            // The most specific cause is the readable part; the wrapping
            // BeanCreationException chain is noise in a startup log.
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.info("Provider '{}' is unavailable and will not be offered: {}", name,
                    cause.getMessage());
            return null;
        }
    }
}
