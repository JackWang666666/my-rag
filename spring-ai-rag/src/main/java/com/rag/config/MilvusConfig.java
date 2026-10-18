package com.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus connection.
 *
 * <p>{@code destroyMethod = "close"} so the gRPC channel and its threads are
 * released on shutdown; without it the JVM could hang on exit after the context
 * closed. Credentials are optional — the compose stack runs Milvus without
 * authentication, but a managed instance (Zilliz) needs a token.
 */
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.token:}")
    private String token;

    @Value("${milvus.database:default}")
    private String database;

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient() {
        ConnectConfig.ConnectConfigBuilder<?, ?> config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(database);
        if (token != null && !token.isBlank()) {
            config.token(token);
        }
        return new MilvusClientV2(config.build());
    }
}
