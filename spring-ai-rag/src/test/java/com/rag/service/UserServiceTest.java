package com.rag.service;

import com.rag.config.RagProperties;
import com.rag.store.Db;
import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirror of the account half of {@code langchain-rag/tests/test_auth.py}.
 *
 * <p>Uses the real {@link BCryptPasswordEncoder} and a real SQLite file rather than
 * mocks: the hashes themselves are the cross-backend contract, and a mock encoder
 * would test nothing that matters here. bcrypt at cost 10 is deliberately slow, so
 * the cases that only need a stored row build the hash once and reuse it.
 */
class UserServiceTest {

    private MetadataStore store;
    private PasswordEncoder encoder;
    private RagProperties props;
    private UserService users;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        RagProperties dbProps = new RagProperties();
        dbProps.setDbPath(dir.resolve("rag.db").toString());
        Db db = new Db(dbProps);
        db.init();

        store = new MetadataStore(db);
        encoder = new BCryptPasswordEncoder();
        props = new RagProperties();
        props.setAdminPassword("admin-password");
        users = new UserService(store, encoder, props);
    }

    @Nested
    class Authenticate {

        @Test
        void succeedsWithTheRightPassword() {
            users.register("alice", "password123");
            MetadataStore.UserRow user = users.authenticate("alice", "password123");
            assertThat(user).isNotNull();
            assertThat(user.username()).isEqualTo("alice");
            assertThat(user.role()).isEqualTo("user");
        }

        @Test
        void failsWithTheWrongPassword() {
            users.register("alice", "password123");
            assertThat(users.authenticate("alice", "password124")).isNull();
        }

        @Test
        void failsForAnUnknownUser() {
            assertThat(users.authenticate("nobody", "password123")).isNull();
        }

        @Test
        void failsOnNullInputRatherThanThrowing() {
            // The login endpoint passes whatever was in the request body.
            assertThat(users.authenticate(null, "password123")).isNull();
            assertThat(users.authenticate("alice", null)).isNull();
            assertThat(users.authenticate(null, null)).isNull();
        }

        @Test
        void isCaseSensitiveOnTheUsername() {
            users.register("alice", "password123");
            assertThat(users.authenticate("Alice", "password123")).isNull();
        }

        @Test
        void aMalformedStoredHashIsAFailedLoginNotAnError() {
            // A row written by a different tool, or a truncated hash. It must not turn
            // the login endpoint into a 500.
            store.createUser("broken", "not-a-bcrypt-hash", "user");
            assertThat(users.authenticate("broken", "password123")).isNull();
        }

        @Test
        void anEmptyStoredHashIsAFailedLogin() {
            store.createUser("broken", "", "user");
            assertThat(users.authenticate("broken", "password123")).isNull();
        }

        @Test
        void aUnicodePasswordRoundTrips() {
            users.register("alice", "密码密码密码");
            assertThat(users.authenticate("alice", "密码密码密码")).isNotNull();
            assertThat(users.authenticate("alice", "密码密码")).isNull();
        }
    }

    @Nested
    @DisplayName("hash interoperability with the Python backend")
    class Interop {

        /**
         * Written by langchain-rag's bcrypt ({@code $2b$}, cost 12) rather than by
         * Spring ({@code $2a$}, cost 10). A user who registered on the Python backend
         * has to be able to log in here, so BCryptPasswordEncoder must accept both.
         */
        private static final String PYTHON_HASH =
                "$2b$12$tKK3Cphy/D3d4hyCW2DQUu227fcEilEenoa90qDiUyQJKFsyVHEIS";
        private static final String PYTHON_UNICODE_HASH =
                "$2b$12$cBZ2kWh.YWzyRC/WSmBz2OUxIA31KCA1KDbVbzZ9YCe0yjELLWB16";

        @Test
        void aPythonWrittenHashVerifies() {
            store.createUser("alice", PYTHON_HASH, "user");
            assertThat(users.authenticate("alice", "password123")).isNotNull();
            assertThat(users.authenticate("alice", "wrong")).isNull();
        }

        @Test
        void aPythonWrittenUnicodeHashVerifies() {
            store.createUser("alice", PYTHON_UNICODE_HASH, "user");
            assertThat(users.authenticate("alice", "密码密码")).isNotNull();
        }

        @Test
        void aHashWrittenHereIsBcryptOfAShapePythonAccepts() {
            // pyca/bcrypt's checkpw accepts $2a$/$2b$/$2y$ at any cost, so the only
            // thing to pin is that this really is a bcrypt hash of that shape.
            users.register("alice", "password123");
            assertThat(store.getUser("alice").hashedPassword())
                    .matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
        }

        @Test
        void theSamePasswordHashesDifferentlyEveryTime() {
            // Per-hash salt: two accounts with the same password must not be
            // recognisable as such from the table.
            users.register("alice", "password123");
            users.register("bob", "password123");
            assertThat(store.getUser("alice").hashedPassword())
                    .isNotEqualTo(store.getUser("bob").hashedPassword());
        }
    }

    @Nested
    class Register {

        @Test
        void storesAHashNotThePassword() {
            users.register("alice", "password123");
            assertThat(store.getUser("alice").hashedPassword()).doesNotContain("password123");
        }

        @Test
        void theNewAccountIsNotAnAdmin() {
            // Self-registration must never grant the admin role.
            assertThat(users.register("alice", "password123").role()).isEqualTo("user");
        }

        @Test
        void theUsernameIsTrimmed() {
            users.register("  alice  ", "password123");
            assertThat(store.getUser("alice")).isNotNull();
        }

        @Test
        void aDuplicateUsernameIsRejected() {
            users.register("alice", "password123");
            assertThatThrownBy(() -> users.register("alice", "password456"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void aDuplicateAfterTrimmingIsAlsoRejected() {
            users.register("alice", "password123");
            assertThatThrownBy(() -> users.register("  alice  ", "password456"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void aRejectedRegistrationCreatesNothing() {
            assertThatThrownBy(() -> users.register("a", "password123"))
                    .isInstanceOf(ApiException.class);
            assertThat(store.userCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the credential policy, identical on both backends")
    class Policy {

        @Test
        void aTwoCharacterUsernameIsAccepted() {
            assertThat(users.register("ab", "password123")).isNotNull();
        }

        @Test
        void aOneCharacterUsernameIsRejected() {
            assertThatThrownBy(() -> users.register("a", "password123"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("between 2 and 50");
        }

        @Test
        void anEmptyUsernameIsRejected() {
            assertThatThrownBy(() -> users.register("", "password123"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void aWhitespaceOnlyUsernameIsRejected() {
            // Rejected after trimming, so "   " is empty rather than 3 characters.
            assertThatThrownBy(() -> users.register("   ", "password123"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void aNullUsernameIsRejected() {
            assertThatThrownBy(() -> users.register(null, "password123"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void aFiftyCharacterUsernameIsAccepted() {
            assertThat(users.register("a".repeat(50), "password123")).isNotNull();
        }

        @Test
        void aFiftyOneCharacterUsernameIsRejected() {
            assertThatThrownBy(() -> users.register("a".repeat(51), "password123"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void theUsernameLimitIsInCharactersNotBytes() {
            // 50 Chinese characters is 150 UTF-8 bytes and still a legal username;
            // the Python backend's Pydantic max_length counts characters too.
            assertThat(users.register("名".repeat(50), "password123")).isNotNull();
        }

        @Test
        void aSixCharacterPasswordIsAccepted() {
            assertThat(users.register("alice", "abc123")).isNotNull();
        }

        @Test
        void aFiveCharacterPasswordIsRejected() {
            assertThatThrownBy(() -> users.register("alice", "abc12"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("at least 6");
        }

        @Test
        void anEmptyPasswordIsRejected() {
            assertThatThrownBy(() -> users.register("alice", ""))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void aNullPasswordIsRejected() {
            assertThatThrownBy(() -> users.register("alice", null))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void aSeventyTwoBytePasswordIsAccepted() {
            assertThat(users.register("alice", "x".repeat(72))).isNotNull();
        }

        @Test
        void aSeventyThreeBytePasswordIsRejected() {
            // Past 72 bytes bcrypt implementations disagree: some truncate, jBCrypt
            // folds the tail into the key schedule, pyca/bcrypt refuses. Rejecting
            // here keeps every account usable from both backends.
            assertThatThrownBy(() -> users.register("alice", "x".repeat(73)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("at most 72 bytes");
        }

        @Test
        void thePasswordLimitCountsUtf8BytesNotCharacters() {
            // 24 Chinese characters is 72 bytes; 25 is 75.
            assertThat(users.register("alice", "密".repeat(24))).isNotNull();
            assertThatThrownBy(() -> users.register("bob", "密".repeat(25)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("at most 72 bytes");
        }

        @Test
        void theConstantsAreWhatTheOtherBackendUses() {
            // app/core/auth.py declares the same four numbers; the login form's
            // maxLength uses 72 as well.
            assertThat(UserService.MIN_USERNAME_LENGTH).isEqualTo(2);
            assertThat(UserService.MAX_USERNAME_LENGTH).isEqualTo(50);
            assertThat(UserService.MIN_PASSWORD_LENGTH).isEqualTo(6);
            assertThat(UserService.MAX_PASSWORD_BYTES).isEqualTo(72);
        }
    }

    @Nested
    class InitAdmin {

        @Test
        void createsTheBootstrapAdmin() {
            users.initAdmin();
            MetadataStore.UserRow admin = users.authenticate("admin", "admin-password");
            assertThat(admin).isNotNull();
            assertThat(admin.role()).isEqualTo("admin");
        }

        @Test
        void usesTheConfiguredUsername() {
            props.setAdminUsername("root");
            users.initAdmin();
            assertThat(store.getUser("root")).isNotNull();
            assertThat(store.getUser("admin")).isNull();
        }

        @Test
        void isIdempotent() {
            // Runs on every startup, and both backends run it against the same table.
            users.initAdmin();
            users.initAdmin();
            users.initAdmin();
            assertThat(store.userCount()).isEqualTo(1);
        }

        @Test
        void doesNotResetARotatedPassword() {
            // Otherwise every restart would silently undo a password change, and the
            // operator's ADMIN_PASSWORD would keep working forever.
            store.createUser("admin", encoder.encode("rotated-password"), "admin");

            users.initAdmin();

            assertThat(users.authenticate("admin", "rotated-password")).isNotNull();
            assertThat(users.authenticate("admin", "admin-password")).isNull();
        }

        @Test
        void doesNotPromoteAnExistingNonAdminAccount() {
            // Someone who registered the name "admin" first must not gain the role.
            store.createUser("admin", encoder.encode("their-password"), "user");
            users.initAdmin();
            assertThat(store.getUser("admin").role()).isEqualTo("user");
        }

        @Test
        void anAdminCreatedByThePythonBackendIsLeftAlone() {
            store.createUser("admin",
                    "$2b$12$tKK3Cphy/D3d4hyCW2DQUu227fcEilEenoa90qDiUyQJKFsyVHEIS", "admin");

            users.initAdmin();

            assertThat(store.userCount()).isEqualTo(1);
            assertThat(users.authenticate("admin", "password123")).isNotNull();
        }
    }
}
