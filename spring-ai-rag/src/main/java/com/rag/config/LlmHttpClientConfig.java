package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.HttpClient;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JettyClientHttpRequestFactory;

import java.time.Duration;

/**
 * The HTTP client, and its timeouts, for the blocking LLM and embedding calls.
 *
 * <p>Spring Boot picks a client by scanning the classpath, and it was picking Jetty —
 * not because anything here asked for it, but because {@code jetty-client} arrived
 * transitively through the Milvus SDK's Hadoop dependencies
 * ({@code hadoop-yarn-client} → {@code websocket-client} → {@code jetty-client}). So
 * an accidental dependency was deciding how long a model is allowed to think, and it
 * decided <em>ten seconds</em> ({@code JettyClientHttpRequestFactory.readTimeout}
 * defaults to 10000ms).
 *
 * <p>Ten seconds is not enough for a reasoning model. Measured against one company
 * gateway, a single entity-extraction call to {@code deepseek-v4-pro} took 38s and
 * to {@code deepseek-v4-flash} 45s, because both spend most of their tokens on
 * reasoning before emitting anything. The result was that every non-streaming call
 * failed: entity extraction (so knowledge graphs came out empty), query analysis
 * (so routing fell back to plain RAG) and LLM reranking (so vector order was
 * returned unchanged). None of it was visible to the user — each caller catches the
 * failure and degrades quietly, which is the right behaviour but made the cause very
 * hard to see. Only the final answer worked, because streaming goes through
 * {@code WebClient}, which has no such default.
 *
 * <p>Raising {@code readTimeout} alone was not enough: the same calls then failed at
 * 30s with {@code Idle timeout expired: 30004/30000 ms}, because that second limit
 * lives on Jetty's {@code HttpClient} rather than on the request and Spring's
 * {@code readTimeout} does not reach it. Hence the client is built here and handed to
 * the factory, so both limits are set in one place.
 *
 * <p>Swapping in the JDK's own {@code HttpClient} to avoid the two-layer problem was
 * tried and reverted: the gateway measured above accepts Jetty's TLS handshake and
 * closes the connection on the JDK client's, failing every request with
 * {@code Remote host terminated the handshake}. So the client is not interchangeable
 * in practice, and Jetty is the one known to work — which is also why it is now a
 * declared dependency in the pom rather than an inherited accident.
 */
@Configuration
@Slf4j
public class LlmHttpClientConfig {

    /**
     * Generous rather than tuned. A too-low value degrades features silently, while
     * a too-high one only matters when the provider has already stopped responding —
     * and the SSE emitter has its own timeout for that case.
     */
    static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Short on purpose: failing to reach the gateway at all should be reported
     * immediately, not after minutes of waiting. Only <em>thinking</em> is slow.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * {@code destroyMethod} because Spring only stops a Jetty client it created
     * itself; one passed to {@link JettyClientHttpRequestFactory} is left running.
     * Started by the factory's {@code afterPropertiesSet}, which is why the factory
     * below is a bean rather than a local.
     */
    @Bean(destroyMethod = "stop")
    public HttpClient llmJettyHttpClient() {
        HttpClient httpClient = new HttpClient();
        // The connector-level limit that Spring's readTimeout cannot reach.
        httpClient.setIdleTimeout(READ_TIMEOUT.toMillis());
        httpClient.setConnectTimeout(CONNECT_TIMEOUT.toMillis());
        return httpClient;
    }

    @Bean
    public JettyClientHttpRequestFactory llmRequestFactory(HttpClient llmJettyHttpClient) {
        JettyClientHttpRequestFactory factory =
                new JettyClientHttpRequestFactory(llmJettyHttpClient);
        // The per-request limit; the idle timeout above is per-read within a request.
        factory.setReadTimeout(READ_TIMEOUT);
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        return factory;
    }

    /**
     * Applies to every {@code RestClient} built from the auto-configured builder,
     * which is what Spring AI's {@code openAiChatModel} and {@code openAiEmbeddingModel}
     * are handed. Nothing else in this application uses {@code RestClient}.
     */
    @Bean
    public RestClientCustomizer llmRestClientCustomizer(
            JettyClientHttpRequestFactory llmRequestFactory) {
        log.info("LLM HTTP client: Jetty (read/idle {}s, connect {}s)",
                READ_TIMEOUT.toSeconds(), CONNECT_TIMEOUT.toSeconds());
        return builder -> builder.requestFactory(llmRequestFactory);
    }
}
