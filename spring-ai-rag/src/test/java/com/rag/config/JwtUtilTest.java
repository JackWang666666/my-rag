package com.rag.config;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirror of the token half of {@code langchain-rag/tests/test_auth.py}.
 *
 * <p>Lives in {@code com.rag.config} so the package-private {@code init()} can be
 * called directly — no Spring context, which would want live Milvus and Neo4j.
 *
 * <p>The claim shape is a cross-backend contract, not an internal detail: a token
 * issued here is presented to the Python backend and vice versa, so {@code sub},
 * {@code role}, {@code exp} and HS256-over-raw-UTF-8-bytes all have to hold.
 */
class JwtUtilTest {

    private static final String SECRET = "a-secret-long-enough-for-hs256!!";

    private static JwtUtil util(String secret) {
        return util(secret, 72);
    }

    private static JwtUtil util(String secret, int expireHours) {
        RagProperties props = new RagProperties();
        props.setJwtSecret(secret);
        props.setAccessTokenExpireHours(expireHours);
        JwtUtil util = new JwtUtil(props);
        util.init();
        return util;
    }

    @Nested
    class SecretLength {

        @Test
        void aSecretOfExactlyThirtyTwoBytesIsAccepted() {
            assertThat(SECRET.getBytes(StandardCharsets.UTF_8)).hasSize(32);
            assertThatCode(() -> util(SECRET)).doesNotThrowAnyException();
        }

        @Test
        void aShortSecretRefusesToStart() {
            // Padding or hashing it would produce tokens the Python backend cannot
            // verify, so failing loudly at startup is the only safe option.
            assertThatThrownBy(() -> util("too-short"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32");
        }

        @Test
        void oneByteShortIsStillRefused() {
            assertThatThrownBy(() -> util("x".repeat(31)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void theLengthIsCountedInBytesNotCharacters() {
            // 11 Chinese characters is 33 UTF-8 bytes: a fine HS256 key, and both
            // backends sign over the raw bytes.
            assertThatCode(() -> util("密".repeat(11))).doesNotThrowAnyException();
            assertThatThrownBy(() -> util("密".repeat(10)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void theMessageSaysHowManyBytesWereGiven() {
            assertThatThrownBy(() -> util("short")).hasMessageContaining("got 5");
        }

        @Test
        void aGeneratedSecretIsAlwaysLongEnough() {
            // RagProperties generates one when none is configured; if it were too
            // short the backend could never start unconfigured.
            RagProperties props = new RagProperties();
            props.resolveSecrets();
            assertThatCode(() -> util(props.getJwtSecret())).doesNotThrowAnyException();
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void theSubjectIsTheUsername() {
            JwtUtil util = util(SECRET);
            assertThat(util.getUsername(util.generateToken("alice", "user"))).isEqualTo("alice");
        }

        @Test
        void theRoleClaimSurvives() {
            JwtUtil util = util(SECRET);
            assertThat(util.getRole(util.generateToken("alice", "admin"))).isEqualTo("admin");
        }

        @Test
        void aUnicodeUsernameSurvives() {
            JwtUtil util = util(SECRET);
            assertThat(util.getUsername(util.generateToken("张三", "user"))).isEqualTo("张三");
        }

        @Test
        void aFreshTokenIsValid() {
            JwtUtil util = util(SECRET);
            assertThat(util.isValid(util.generateToken("alice", "user"))).isTrue();
        }

        @Test
        void theExpiryIsSetFromTheConfiguredLifetime() {
            JwtUtil util = util(SECRET, 1);
            Claims claims = util.parseToken(util.generateToken("alice", "user"));
            long lifetimeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            assertThat(lifetimeMs).isEqualTo(60L * 60L * 1000L);
        }

        @Test
        void anIssuedAtIsAlwaysPresent() {
            JwtUtil util = util(SECRET);
            assertThat(util.parseToken(util.generateToken("alice", "user")).getIssuedAt())
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the wire format the Python backend has to accept")
    class WireFormat {

        @Test
        void isThreeBase64UrlSegments() {
            assertThat(util(SECRET).generateToken("alice", "user").split("\\.")).hasSize(3);
        }

        @Test
        void theAlgorithmIsHs256() {
            // python-jose is told `algorithms=["HS256"]` and rejects anything else.
            String token = util(SECRET).generateToken("alice", "user");
            String header = new String(
                    Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
            assertThat(header).contains("HS256");
        }

        @Test
        void thePayloadCarriesSubRoleAndExp() {
            String token = util(SECRET).generateToken("alice", "admin");
            String payload = new String(
                    Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            assertThat(payload).contains("\"sub\":\"alice\"").contains("\"role\":\"admin\"")
                    .contains("\"exp\":");
        }
    }

    @Nested
    class Rejection {

        @Test
        void anExpiredTokenIsRejected() {
            // A negative lifetime puts exp an hour in the past, which is well outside
            // any clock skew allowance.
            JwtUtil util = util(SECRET, -1);
            String token = util.generateToken("alice", "user");
            assertThat(util.parseToken(token)).isNull();
            assertThat(util.isValid(token)).isFalse();
            assertThat(util.getUsername(token)).isNull();
        }

        @Test
        void aTokenSignedWithAnotherSecretIsRejected() {
            String token = util(SECRET).generateToken("alice", "user");
            JwtUtil other = util("a-completely-different-secret!!!!");
            assertThat(other.parseToken(token)).isNull();
        }

        @Test
        void aTamperedSignatureIsRejected() {
            JwtUtil util = util(SECRET);
            String[] parts = util.generateToken("alice", "user").split("\\.");
            // The first character, not the last: a 32-byte signature is 43 base64url
            // characters, so the last one's low two bits are discarded on decode and
            // changing them would leave the signature bytes untouched.
            char first = parts[2].charAt(0);
            String flipped = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);

            assertThat(util.parseToken(parts[0] + "." + parts[1] + "." + flipped)).isNull();
        }

        @Test
        void aTamperedPayloadIsRejected() {
            // The point of signing: escalating your own role must not verify.
            JwtUtil util = util(SECRET);
            String[] parts = util.generateToken("alice", "user").split("\\.");
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"sub\":\"alice\",\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));

            assertThat(util.parseToken(parts[0] + "." + forged + "." + parts[2])).isNull();
        }

        @Test
        void anUnsignedTokenIsRejected() {
            // alg=none: the classic bypass.
            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"sub\":\"alice\",\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));

            assertThat(util(SECRET).parseToken(header + "." + payload + ".")).isNull();
        }

        @Test
        void garbageIsRejectedRatherThanThrown() {
            // The filter calls this on whatever arrived in the Authorization header,
            // so a malformed value has to be a 401 and not a 500.
            JwtUtil util = util(SECRET);
            assertThat(util.parseToken("not-a-token")).isNull();
            assertThat(util.parseToken("a.b.c")).isNull();
            assertThat(util.parseToken("")).isNull();
            assertThat(util.parseToken(null)).isNull();
        }

        @Test
        void garbagePassedToTheAccessorsIsNullToo() {
            JwtUtil util = util(SECRET);
            assertThat(util.getUsername(null)).isNull();
            assertThat(util.getRole(null)).isNull();
            assertThat(util.isValid(null)).isFalse();
        }
    }

    @Nested
    class RoleDefaulting {

        @Test
        void aTokenWithoutARoleClaimReadsAsUser() {
            // The least-privilege default. A token issued before the role claim
            // existed must not be treated as an admin.
            JwtUtil util = util(SECRET);
            assertThat(util.getRole(util.generateToken("alice", null))).isEqualTo("user");
        }

        @Test
        void anInvalidTokenGivesNullNotTheDefault() {
            // Null means "no identity at all"; "user" would mean an authenticated
            // ordinary user, which a garbage token must never produce.
            assertThat(util(SECRET).getRole("garbage")).isNull();
        }
    }
}
