package com.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.BootstrapContext;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key placeholder pulls in two opposite directions: it has to be present enough
 * that Spring AI's five eager OpenAI beans can be constructed, and absent enough
 * that {@code openai} is never offered as a working provider. A change that breaks
 * the first makes the application refuse to start without a key; a change that
 * breaks the second makes every OpenAI call 401.
 *
 * <p>The base-url cases pin the other half: {@code OpenAiApi} appends {@code /v1}
 * to whatever it is handed, so a URL that already carries the suffix has to lose it,
 * and one that only looks like it does has to keep it.
 */
class OpenAiEnvironmentPostProcessorTest {

    private static final String KEY = OpenAiEnvironmentPostProcessor.API_KEY_PROPERTY;

    private static final String BASE_URL = OpenAiEnvironmentPostProcessor.BASE_URL_PROPERTY;

    private final OpenAiEnvironmentPostProcessor processor =
            // DeferredLogFactory's single abstract method is getLog(Supplier<Log>);
            // nothing here needs the deferral, so resolve it immediately.
            new OpenAiEnvironmentPostProcessor(Supplier::get);

    /** Runs the post-processor over an environment holding the given api-key value. */
    private ConfigurableEnvironment withApiKey(String apiKey) {
        return withProperties(apiKey == null ? Map.of() : Map.of(KEY, apiKey));
    }

    /** Runs the post-processor over an environment holding the given properties. */
    private ConfigurableEnvironment withProperties(Map<String, Object> properties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        if (!properties.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("test", properties));
        }
        processor.postProcessEnvironment(environment, null);
        return environment;
    }

    /** The base URL the OpenAI client would end up using, given this configured one. */
    private String effectiveBaseUrl(String configured) {
        return withProperties(Map.of(BASE_URL, configured)).getProperty(BASE_URL);
    }

    @Nested
    @DisplayName("no key: a placeholder is supplied so the context can start")
    class Substitutes {

        @Test
        void whenTheKeyIsAbsentEntirely() {
            // application.yml uses ${OPENAI_API_KEY:}, so in practice the property
            // exists and is empty -- but a deployment overriding the yml could leave
            // it genuinely unset.
            assertThat(withApiKey(null).getProperty(KEY))
                    .isEqualTo(OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY);
        }

        @Test
        void whenTheKeyIsEmpty() {
            assertThat(withApiKey("").getProperty(KEY))
                    .isEqualTo(OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY);
        }

        @Test
        void whenTheKeyIsWhitespaceOnly() {
            // `OPENAI_API_KEY=" "` is not a key, but it does satisfy the Assert.hasText
            // in Spring AI's factory methods -- so without the blank check the beans
            // would build and then 401 instead of falling back to Ollama.
            assertThat(withApiKey("   ").getProperty(KEY))
                    .isEqualTo(OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY);
        }

        @Test
        void thePlaceholderIsNotBlank() {
            // The entire point: Spring AI asserts hasText on the key. A blank
            // placeholder would place us right back at the startup crash.
            assertThat(OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY).isNotBlank();
        }

        @Test
        void thePlaceholderIsNotMistakableForARealKey() {
            // If it ever reaches an upstream error message, it should read as a
            // misconfiguration rather than as a leaked credential.
            assertThat(OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY)
                    .doesNotStartWith("sk-");
        }
    }

    @Nested
    @DisplayName("a real key: nothing is touched")
    class LeavesAlone {

        @Test
        void keepsTheConfiguredValue() {
            assertThat(withApiKey("sk-real-key").getProperty(KEY)).isEqualTo("sk-real-key");
        }

        @Test
        void doesNotAddAPropertySource() {
            assertThat(withApiKey("sk-real-key").getPropertySources()
                    .contains(OpenAiEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                    .isFalse();
        }

        @Test
        void aCompanyGatewayKeyIsJustAsRealAsAnOpenAiOne() {
            // Keys issued by an OpenAI-compatible proxy do not look like sk-...; the
            // check is "non-blank", never a format guess.
            assertThat(withApiKey("corp_7f3a9d").getProperty(KEY)).isEqualTo("corp_7f3a9d");
        }
    }

    @Nested
    @DisplayName("the availability gate the registry uses")
    class Gate {

        @Test
        void aPlaceholderKeyMeansOpenAiIsNotConfigured() {
            // The beans exist and are injectable; that must not be read as the
            // provider being usable.
            assertThat(ProviderBeans.openAiConfigured(withApiKey(""))).isFalse();
        }

        @Test
        void aRealKeyMeansOpenAiIsConfigured() {
            assertThat(ProviderBeans.openAiConfigured(withApiKey("sk-real-key"))).isTrue();
        }

        @Test
        void anAbsentKeyMeansOpenAiIsNotConfigured() {
            assertThat(ProviderBeans.openAiConfigured(withApiKey(null))).isFalse();
        }

        @Test
        void aPlaceholderWithStrayWhitespaceStillCountsAsNotConfigured() {
            // Property sources can round-trip through files that add padding.
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addLast(new MapPropertySource("test",
                    Map.of(KEY, "  " + OpenAiEnvironmentPostProcessor.PLACEHOLDER_API_KEY + " ")));
            assertThat(ProviderBeans.openAiConfigured(environment)).isFalse();
        }

        @Test
        void aBlankKeyThatWasNeverPostProcessedIsAlsoNotConfigured() {
            // The gate must not depend on the post-processor having run: unit tests
            // and slices build environments directly.
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources()
                    .addLast(new MapPropertySource("test", Map.of(KEY, "   ")));
            assertThat(ProviderBeans.openAiConfigured(environment)).isFalse();
        }
    }

    @Nested
    @DisplayName("base-url: the /v1 Spring AI adds itself is never doubled")
    class BaseUrl {

        @Test
        void aGatewayUrlCopiedFromItsDocsHasTheSuffixRemoved() {
            // The suffix-carrying form is what every OpenAI-compatible gateway
            // publishes, and what OPENAI_API_BASE holds on the LangChain side.
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/v1"))
                    .isEqualTo("https://gateway.corp.example.com");
        }

        @Test
        void aTrailingSlashAfterTheSuffixIsAlsoRemoved() {
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/v1/"))
                    .isEqualTo("https://gateway.corp.example.com");
        }

        @Test
        void aTrailingSlashOnItsOwnIsRemoved() {
            // OpenAiApi concatenates rather than resolves, so this would otherwise
            // request //v1/chat/completions.
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/"))
                    .isEqualTo("https://gateway.corp.example.com");
        }

        @Test
        void aCorrectUrlIsLeftExactlyAsItIs() {
            assertThat(effectiveBaseUrl("https://api.openai.com"))
                    .isEqualTo("https://api.openai.com");
        }

        @Test
        void surroundingWhitespaceIsRemoved() {
            // Reading the value out of a .env file keeps whatever padding is there,
            // and a space before https:// is not a URL.
            assertThat(effectiveBaseUrl("  https://api.openai.com  "))
                    .isEqualTo("https://api.openai.com");
        }

        @Test
        void aPathEndingInSomethingOtherThanV1IsNotTouched() {
            // Azure-style and vendor-prefixed deployments really do serve from a
            // path; only a whole /v1 segment is ours to remove.
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/openai/v1beta"))
                    .isEqualTo("https://gateway.corp.example.com/openai/v1beta");
        }

        @Test
        void onlyAWholeSegmentCounts() {
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/apiv1"))
                    .isEqualTo("https://gateway.corp.example.com/apiv1");
        }

        @Test
        void aPathThatStillHasSegmentsLeftKeepsThem() {
            assertThat(effectiveBaseUrl("https://gateway.corp.example.com/openai/v1"))
                    .isEqualTo("https://gateway.corp.example.com/openai");
        }

        @Test
        void nothingConfiguredIsLeftToApplicationYmlsDefault() {
            // The yml supplies ${OPENAI_API_BASE:https://api.openai.com}; overriding
            // an absent value here would shadow that.
            assertThat(withApiKey("sk-real-key").getProperty(BASE_URL)).isNull();
        }

        @Test
        void thePerModelOverridesAreTrimmedToo() {
            // Splitting embeddings onto a second gateway is the usual reason to set
            // these, and that second URL arrives in exactly the same suffixed form.
            ConfigurableEnvironment environment = withProperties(Map.of(
                    BASE_URL, "https://chat.corp.example.com/v1",
                    "spring.ai.openai.embedding.base-url", "https://embed.corp.example.com/v1/"));
            assertThat(environment.getProperty(BASE_URL))
                    .isEqualTo("https://chat.corp.example.com");
            assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
                    .isEqualTo("https://embed.corp.example.com");
        }

        @Test
        void everyBaseUrlSpringAiReadsIsCovered() {
            // A per-model URL left out of the list keeps the /v1/v1 bug for whoever
            // sets it, and would look like a gateway fault rather than ours.
            assertThat(OpenAiEnvironmentPostProcessor.BASE_URL_PROPERTIES)
                    .containsExactly("spring.ai.openai.base-url",
                            "spring.ai.openai.chat.base-url",
                            "spring.ai.openai.embedding.base-url");
        }

        @Test
        void aUrlThatIsNothingButTheSuffixIsLeftAlone() {
            // Trimming this to "" would produce a request to a bare path and an
            // error naming no host at all. Better to fail against what was typed.
            assertThat(effectiveBaseUrl("/v1")).isEqualTo("/v1");
        }
    }

    @Nested
    class Wiring {

        @Test
        void runsAfterConfigDataSoTheKeyIsReadable() {
            // ConfigDataEnvironmentPostProcessor is HIGHEST_PRECEDENCE + 10. Reading
            // the key before application.yml is loaded would see nothing and stamp a
            // placeholder over a key that was configured there.
            assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        }

        @Test
        void isRegisteredInSpringFactories() throws IOException {
            // An EnvironmentPostProcessor that is never registered does nothing, and
            // the only symptom is the original startup crash returning -- which no
            // other test in this class would notice.
            Properties factories = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
                assertThat(in).as("META-INF/spring.factories on the classpath").isNotNull();
                factories.load(in);
            }
            assertThat(factories.getProperty(
                    "org.springframework.boot.env.EnvironmentPostProcessor"))
                    .contains(OpenAiEnvironmentPostProcessor.class.getName());
        }

        @Test
        void hasAConstructorBootCanCall() {
            // Boot instantiates spring.factories post-processors reflectively and can
            // only supply DeferredLogFactory, BootstrapContext and BootstrapRegistry.
            // A parameter outside that set fails at startup, not here, so pin it.
            Set<Class<?>> injectable = Set.of(DeferredLogFactory.class,
                    ConfigurableBootstrapContext.class, BootstrapContext.class,
                    BootstrapRegistry.class);
            Constructor<?>[] constructors =
                    OpenAiEnvironmentPostProcessor.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterTypes()).allSatisfy(
                    type -> assertThat(injectable).contains(type));
        }
    }
}
