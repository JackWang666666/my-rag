package com.rag.config;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JettyClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure this guards against is invisible: with the default ten second read
 * timeout every reasoning-model call fails, each caller catches it and degrades
 * quietly, and the application looks like it is working. So the assertions are about
 * the timeouts actually reaching the client, not just about the constants.
 *
 * <p>There are two independent limits — Spring's per-request {@code readTimeout} and
 * Jetty's connector-level idle timeout — and raising only the first left the calls
 * failing at 30s instead of 10s. Both are pinned here for that reason.
 */
class LlmHttpClientConfigTest {

    private final LlmHttpClientConfig config = new LlmHttpClientConfig();

    /** As Spring wires it: client into factory, factory into the customizer. */
    private JettyClientHttpRequestFactory requestFactory() {
        return config.llmRequestFactory(config.llmJettyHttpClient());
    }

    @Test
    void theReadTimeoutOutlastsAReasoningModel() {
        // Measured worst case against a company gateway was 45s for one extraction
        // call. Anything in the same order of magnitude as Jetty's 10s default would
        // put us straight back to silently empty knowledge graphs.
        assertThat(LlmHttpClientConfig.READ_TIMEOUT).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void theConnectTimeoutStaysShort() {
        // An unreachable gateway should be reported promptly; only *thinking* is slow.
        assertThat(LlmHttpClientConfig.CONNECT_TIMEOUT)
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theIdleTimeoutIsRaisedOnTheClientToo() {
        // This is the limit that Spring's readTimeout does not reach. Left at Jetty's
        // 30s default it fails the same calls with "Idle timeout expired", which reads
        // like a gateway fault rather than a setting of ours.
        assertThat(config.llmJettyHttpClient().getIdleTimeout())
                .isEqualTo(LlmHttpClientConfig.READ_TIMEOUT.toMillis());
    }

    @Test
    void theRequestTimeoutIsRaisedOnTheFactory() throws Exception {
        // The default is 10000ms and lives in a private field with no getter, so
        // reflection is the only way to tell a raised value from the default.
        Field field = JettyClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        field.setAccessible(true);
        assertThat((long) field.get(requestFactory()))
                .isEqualTo(LlmHttpClientConfig.READ_TIMEOUT.toMillis());
    }

    @Test
    void theClientIsTheOneTheGatewayAccepts() {
        // Not a style preference: the gateway this was measured against closes the
        // connection on the JDK client's TLS handshake ("Remote host terminated the
        // handshake") and completes Jetty's. Switching client is a live-traffic
        // change, not a refactor.
        assertThat(requestFactory()).isInstanceOf(JettyClientHttpRequestFactory.class);
    }

    @Test
    void theClientIsStoppedOnShutdown() {
        // JettyClientHttpRequestFactory.destroy() only stops a client it created
        // itself, so ours needs its own destroyMethod or its threads outlive the
        // context.
        assertThat(beanDestroyMethod("llmJettyHttpClient")).isEqualTo("stop");
        assertThat(HttpClient.class).hasPublicMethods("stop");
    }

    private static String beanDestroyMethod(String methodName) {
        try {
            return LlmHttpClientConfig.class.getMethod(methodName)
                    .getAnnotation(org.springframework.context.annotation.Bean.class)
                    .destroyMethod();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no such @Bean method: " + methodName, e);
        }
    }

    @Test
    void theCustomizerReplacesTheRequestFactory() throws Exception {
        // Boot's default factory is chosen by classpath scanning, so the point of the
        // customizer is to override whatever that scan picked -- here, an unconfigured
        // Jetty client with both timeouts left at their defaults.
        RestClient.Builder builder = RestClient.builder();
        JettyClientHttpRequestFactory expected = requestFactory();
        config.llmRestClientCustomizer(expected).customize(builder);

        RestClient client = builder.build();
        Field field = client.getClass().getDeclaredField("clientRequestFactory");
        field.setAccessible(true);
        assertThat((ClientHttpRequestFactory) field.get(client)).isSameAs(expected);
    }

    @Test
    void theCustomizerIsIdempotent() {
        // Boot applies every RestClientCustomizer bean to every builder; applying
        // this one twice must not throw.
        RestClient.Builder builder = RestClient.builder();
        RestClientCustomizerApplier.applyTwice(config, requestFactory(), builder);
        assertThat(builder.build()).isNotNull();
    }

    /** Keeps the double-application detail out of the test body. */
    private static final class RestClientCustomizerApplier {
        static void applyTwice(LlmHttpClientConfig config, JettyClientHttpRequestFactory factory,
                               RestClient.Builder builder) {
            config.llmRestClientCustomizer(factory).customize(builder);
            config.llmRestClientCustomizer(factory).customize(builder);
        }
    }
}
