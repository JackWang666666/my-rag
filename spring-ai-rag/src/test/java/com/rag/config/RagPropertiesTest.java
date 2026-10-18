package com.rag.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirror of {@code langchain-rag/tests/test_config.py}.
 *
 * <p>In the same package so the package-private {@code resolveSecrets()} can be
 * called directly, and with no Spring context: the point is the plain-Java defaults
 * and parsing, and every default here has a counterpart in {@code config.py} that
 * must agree with it.
 */
class RagPropertiesTest {

    private static final String LONG_SECRET = "x".repeat(32);

    /** Configured just enough that nothing is generated and nothing warns. */
    private static RagProperties configured() {
        RagProperties props = new RagProperties();
        props.setJwtSecret(LONG_SECRET);
        props.setAdminPassword("hunter2hunter2");
        props.setCorsOrigins("https://rag.example.com");
        props.resolveSecrets();
        return props;
    }

    private static RagProperties withCors(String value) {
        RagProperties props = new RagProperties();
        props.setCorsOrigins(value);
        return props;
    }

    @Nested
    class Defaults {

        @Test
        void theDbPathIsSharedWithThePythonBackend() {
            // Relative to each backend's own directory, so both land on
            // <project>/data/rag.db.
            assertThat(new RagProperties().getDbPath()).isEqualTo("../data/rag.db");
        }

        @Test
        void theTokenLifetimeMatchesThePythonDefault() {
            assertThat(new RagProperties().getAccessTokenExpireHours()).isEqualTo(72);
        }

        @Test
        void theDefaultCorsOriginIsTheDevFrontend() {
            assertThat(new RagProperties().corsOriginList())
                    .containsExactly("http://localhost:3000");
        }

        @Test
        void theChunkingDefaultsMatchThePythonOnes() {
            // Different values would chunk the same upload differently, so the two
            // backends' knowledge bases would stop being comparable.
            RagProperties props = new RagProperties();
            assertThat(props.getChunkSize()).isEqualTo(1000);
            assertThat(props.getChunkOverlap()).isEqualTo(200);
        }

        @Test
        void theRetrievalDefaultsMatchThePythonOnes() {
            RagProperties props = new RagProperties();
            assertThat(props.getRerankerProvider()).isEqualTo("llm");
            assertThat(props.getRerankTopK()).isEqualTo(5);
            assertThat(props.getRerankThreshold()).isEqualTo(3.0);
            assertThat(props.getEmbeddingBatchSize()).isEqualTo(64);
            assertThat(props.getGraphMaxChunks()).isEqualTo(40);
        }

        @Test
        void thereIsNoHardcodedSecret() {
            // A fixed fallback would let anyone forge tokens against a default
            // deployment.
            assertThat(new RagProperties().getJwtSecret()).isEmpty();
            assertThat(new RagProperties().getAdminPassword()).isEmpty();
        }

        @Test
        void theAdminUsernameDefaultsToAdmin() {
            assertThat(new RagProperties().getAdminUsername()).isEqualTo("admin");
        }
    }

    @Nested
    class MaxUploadBytes {

        @Test
        void isTheMegabyteValueInBytes() {
            assertThat(new RagProperties().maxUploadBytes()).isEqualTo(50L * 1024 * 1024);
        }

        @Test
        void doesNotOverflowOnALargeLimit() {
            // 4096 MB overflows a 32-bit int; the cast to long has to happen first.
            RagProperties props = new RagProperties();
            props.setMaxUploadMb(4096);
            assertThat(props.maxUploadBytes()).isEqualTo(4096L * 1024 * 1024);
        }
    }

    @Nested
    class CorsOriginList {

        @Test
        void aSingleOrigin() {
            assertThat(withCors("http://a.test").corsOriginList())
                    .containsExactly("http://a.test");
        }

        @Test
        void aCommaSeparatedListIsSplitAndTrimmed() {
            assertThat(withCors(" http://a.test , http://b.test ").corsOriginList())
                    .containsExactly("http://a.test", "http://b.test");
        }

        @Test
        void emptyEntriesAreDropped() {
            assertThat(withCors("http://a.test,,  ,").corsOriginList())
                    .containsExactly("http://a.test");
        }

        @Test
        void anEmptyValueYieldsNoOrigins() {
            // No origin allowed is the safe reading of "nothing configured". This used
            // to fall back to http://localhost:3000, which quietly re-allowed the dev
            // origin in a deployment where someone had blanked the variable — and
            // diverged from Settings.cors_origin_list on the Python side.
            assertThat(withCors("").corsOriginList()).isEmpty();
            assertThat(withCors("   ").corsOriginList()).isEmpty();
        }

        @Test
        void aWildcardStaysASingleEntry() {
            assertThat(withCors("*").corsOriginList()).containsExactly("*");
        }
    }

    @Nested
    class GeneratedSecrets {

        @Test
        void anUnsetJwtSecretIsGenerated() {
            RagProperties props = new RagProperties();
            props.setAdminPassword("set");
            props.resolveSecrets();

            assertThat(props.getJwtSecret()).isNotBlank();
            assertThat(props.isJwtSecretGenerated()).isTrue();
        }

        @Test
        void aGeneratedSecretIsLongEnoughForHs256() {
            RagProperties props = new RagProperties();
            props.resolveSecrets();
            assertThat(props.getJwtSecret().length()).isGreaterThanOrEqualTo(32);
        }

        @Test
        void twoInstancesGenerateDifferentSecrets() {
            RagProperties first = new RagProperties();
            first.resolveSecrets();
            RagProperties second = new RagProperties();
            second.resolveSecrets();
            assertThat(first.getJwtSecret()).isNotEqualTo(second.getJwtSecret());
        }

        @Test
        void aGeneratedSecretIsUrlSafeBase64() {
            // It gets logged and pasted into a .env, so `+`, `/` and `=` would be a
            // nuisance at best and truncated at worst.
            RagProperties props = new RagProperties();
            props.resolveSecrets();
            assertThat(props.getJwtSecret()).matches("[A-Za-z0-9_-]+");
        }

        @Test
        void aConfiguredSecretIsKept() {
            RagProperties props = configured();
            assertThat(props.getJwtSecret()).isEqualTo(LONG_SECRET);
            assertThat(props.isJwtSecretGenerated()).isFalse();
        }

        @Test
        void anUnsetAdminPasswordIsGenerated() {
            RagProperties props = new RagProperties();
            props.setJwtSecret(LONG_SECRET);
            props.resolveSecrets();

            assertThat(props.getAdminPassword()).isNotBlank();
            assertThat(props.isAdminPasswordGenerated()).isTrue();
        }

        @Test
        void aConfiguredAdminPasswordIsKept() {
            RagProperties props = configured();
            assertThat(props.getAdminPassword()).isEqualTo("hunter2hunter2");
            assertThat(props.isAdminPasswordGenerated()).isFalse();
        }

        @Test
        void aWhitespaceOnlyValueCountsAsUnset() {
            // Otherwise `JWT_SECRET=" "` becomes a one-byte signing key.
            RagProperties props = new RagProperties();
            props.setJwtSecret("   ");
            props.setAdminPassword("\t");
            props.resolveSecrets();

            assertThat(props.isJwtSecretGenerated()).isTrue();
            assertThat(props.isAdminPasswordGenerated()).isTrue();
        }

        @Test
        void aNullValueCountsAsUnset() {
            RagProperties props = new RagProperties();
            props.setJwtSecret(null);
            props.setAdminPassword(null);
            props.resolveSecrets();

            assertThat(props.getJwtSecret()).isNotBlank();
            assertThat(props.getAdminPassword()).isNotBlank();
        }
    }

    @Nested
    class StartupWarnings {

        @Test
        void aFullyConfiguredDeploymentWarnsAboutNothing() {
            assertThat(configured().startupWarnings()).isEmpty();
        }

        @Test
        void aGeneratedJwtSecretIsReported() {
            RagProperties props = new RagProperties();
            props.setAdminPassword("set");
            props.resolveSecrets();
            assertThat(props.startupWarnings())
                    .anyMatch(w -> w.contains("JWT_SECRET is not set"));
        }

        @Test
        void aGeneratedAdminPasswordIsPrintedSoLoginIsPossible() {
            // It only exists in this process's memory, so if it is not logged nobody
            // can sign in at all.
            RagProperties props = new RagProperties();
            props.setJwtSecret(LONG_SECRET);
            props.resolveSecrets();

            assertThat(props.startupWarnings())
                    .anyMatch(w -> w.contains(props.getAdminPassword()));
        }

        @Test
        void aWildcardCorsOriginIsReported() {
            // Browsers refuse to send credentials to a wildcard origin, so the
            // frontend's Authorization header would never arrive.
            RagProperties props = configured();
            props.setCorsOrigins("*");
            assertThat(props.startupWarnings()).anyMatch(w -> w.contains("CORS_ORIGINS contains"));
        }

        @Test
        void aWildcardAmongRealOriginsIsStillReported() {
            RagProperties props = configured();
            props.setCorsOrigins("http://localhost:3000,*");
            assertThat(props.startupWarnings()).anyMatch(w -> w.contains("CORS_ORIGINS contains"));
        }

        @Test
        void anEmptyCorsOriginIsReported() {
            // Nothing gets through, so the frontend cannot call the backend at all —
            // a silent failure on every request without this.
            RagProperties props = configured();
            props.setCorsOrigins("");
            assertThat(props.startupWarnings()).anyMatch(w -> w.contains("CORS_ORIGINS is empty"));
        }

        @Test
        void aRealOriginDoesNotWarn() {
            RagProperties props = configured();
            props.setCorsOrigins("https://a.test,https://b.test");
            assertThat(props.startupWarnings()).isEmpty();
        }

        @Test
        void anUnconfiguredDeploymentReportsEverythingAtOnce() {
            RagProperties props = new RagProperties();
            props.setCorsOrigins("*");
            props.resolveSecrets();
            assertThat(props.startupWarnings()).hasSize(3);
        }
    }
}
