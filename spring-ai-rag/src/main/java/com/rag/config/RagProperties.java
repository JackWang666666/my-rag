package com.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * All `rag.*` configuration.
 *
 * <p>Mirrors {@code langchain-rag/app/core/config.py} — the defaults on both
 * sides must stay identical, see {@code docs/CONTRACT.md}.
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Data
@Slf4j
public class RagProperties {

    /** Shared SQLite metadata store; must match the Python backend's DB_PATH. */
    private String dbPath = "../data/rag.db";

    private String llmProvider = "openai";
    private String embeddingProvider = "openai";
    private int chunkSize = 1000;
    private int chunkOverlap = 200;
    private String uploadDir = "./uploads";

    private String jwtSecret = "";
    private int accessTokenExpireHours = 72;
    private String adminUsername = "admin";
    private String adminPassword = "";
    private String corsOrigins = "http://localhost:3000";

    private int maxUploadMb = 50;
    private int embeddingBatchSize = 64;

    private String rerankerProvider = "llm";
    private String rerankerModel = "BAAI/bge-reranker-v2-m3";
    private int rerankTopK = 5;
    private double rerankThreshold = 3.0;

    /**
     * Below this, {@code ChatService} treats retrieval as unreliable and lets the
     * model fall back to general knowledge instead of refusing. Raised from an
     * earlier 0.5: a lexically-matched but off-topic chunk can score in the
     * 0.5-0.65 range on count/route-confidence alone, before the content is
     * actually relevant — see {@code ChatService.LOW_CONFIDENCE_FALLBACK_PROMPT}.
     */
    private double lowConfidenceThreshold = 0.65;

    private int graphMaxChunks = 40;

    /** True when {@link #jwtSecret} was generated because none was configured. */
    private boolean jwtSecretGenerated;

    /** True when {@link #adminPassword} was generated because none was configured. */
    private boolean adminPasswordGenerated;

    @PostConstruct
    void resolveSecrets() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            jwtSecret = randomToken(48);
            jwtSecretGenerated = true;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            adminPassword = randomToken(12);
            adminPasswordGenerated = true;
        }
        for (String warning : startupWarnings()) {
            log.warn(warning);
        }
    }

    /**
     * Allowed browser origins, parsed from the comma-separated property.
     *
     * <p>An empty value gives no origins rather than falling back to the dev
     * default: quietly re-allowing {@code http://localhost:3000} in a deployment
     * where someone blanked the variable is worse than allowing nothing, and
     * {@code Settings.cors_origin_list} on the Python side does the same.
     * {@link #startupWarnings()} points it out.
     */
    public List<String> corsOriginList() {
        List<String> origins = new ArrayList<>();
        for (String part : corsOrigins.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return origins;
    }

    public long maxUploadBytes() {
        return (long) maxUploadMb * 1024L * 1024L;
    }

    /** Human-readable warnings about insecure or ephemeral defaults. */
    public List<String> startupWarnings() {
        List<String> warnings = new ArrayList<>();
        if (jwtSecretGenerated) {
            warnings.add("JWT_SECRET is not set: generated a random secret. "
                    + "All tokens are invalidated on restart and tokens are not shared with the "
                    + "Python backend. Set JWT_SECRET to the same value in both backends.");
        }
        if (adminPasswordGenerated) {
            warnings.add("ADMIN_PASSWORD is not set: the bootstrap admin password is '"
                    + adminPassword + "'. Set ADMIN_PASSWORD to control it.");
        }
        List<String> origins = corsOriginList();
        if (origins.contains("*")) {
            warnings.add("CORS_ORIGINS contains '*': browsers refuse to send credentials to a "
                    + "wildcard origin. List the real frontend origins instead.");
        }
        if (origins.isEmpty()) {
            warnings.add("CORS_ORIGINS is empty: no browser origin is allowed, so the frontend "
                    + "cannot call this backend. Set it to the frontend's origin.");
        }
        return warnings;
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
