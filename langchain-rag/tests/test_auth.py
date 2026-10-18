"""Tests for app.core.auth.

The username/password limits are duplicated in the Spring backend's
`UserService`. They have to stay identical: both backends write the same `users`
table, so a credential one accepts and the other rejects is an account that only
works on half the system. `TestPolicyConstants` pins the values so a change on
this side is at least visible.
"""

from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException
from jose import jwt

from app.core import auth
from app.core.config import settings


@pytest.fixture()
def registered(store):
    """A store with one ordinary user, 'alice'."""
    auth.register_user("alice", "password123")
    return store


class TestPolicyConstants:
    def test_values_match_the_spring_backend(self):
        assert auth.MIN_USERNAME_LENGTH == 2
        assert auth.MAX_USERNAME_LENGTH == 50
        assert auth.MIN_PASSWORD_LENGTH == 6
        assert auth.MAX_PASSWORD_BYTES == 72


class TestPasswordHashing:
    def test_hash_is_not_the_password(self):
        hashed = auth.hash_password("password123")
        assert hashed != "password123"
        # `$2b$` is what Spring's BCryptPasswordEncoder.matches() expects to be
        # able to read back out of the shared users table.
        assert hashed.startswith("$2b$")

    def test_verify_accepts_the_right_password(self):
        assert auth.verify_password("password123", auth.hash_password("password123"))

    def test_verify_rejects_the_wrong_password(self):
        assert not auth.verify_password("nope", auth.hash_password("password123"))

    def test_salted_so_two_hashes_differ(self):
        assert auth.hash_password("same") != auth.hash_password("same")

    def test_a_malformed_stored_hash_is_a_failed_login_not_a_crash(self):
        # A row written by another tool must not 500 the login endpoint.
        assert not auth.verify_password("password123", "not-a-bcrypt-hash")

    def test_unicode_password(self):
        hashed = auth.hash_password("密码密码")
        assert auth.verify_password("密码密码", hashed)

    def test_a_2a_prefixed_hash_verifies(self):
        # The shape Spring's BCryptPasswordEncoder writes: `$2a$`, cost 10, where
        # this backend writes `$2b$` at cost 12. A user registered on the Spring
        # backend must still be able to log in here.
        spring_hash = "$2a$10$WeJJOMB2Rm0x1tUEGznK6.AyJ.v8NY3h1gPli/Jbze7t.ZuBE9Ld2"
        assert auth.verify_password("password123", spring_hash)
        assert not auth.verify_password("wrong", spring_hash)

    def test_an_over_long_password_is_a_failed_login_not_a_crash(self):
        # bcrypt refuses over 72 bytes. Registration rejects those, but a login
        # attempt with one still has to come back as a plain denial.
        hashed = auth.hash_password("password123")
        assert not auth.verify_password("x" * 200, hashed)


class TestRegister:
    def test_creates_a_user_with_the_user_role(self, store):
        assert auth.register_user("alice", "password123") == {
            "username": "alice", "role": "user",
        }
        assert store.get_user("alice")["role"] == "user"

    def test_password_is_stored_hashed(self, store):
        auth.register_user("alice", "password123")
        assert store.get_user("alice")["hashed_password"] != "password123"

    def test_username_is_trimmed(self, store):
        auth.register_user("  alice  ", "password123")
        assert store.get_user("alice") is not None

    @pytest.mark.parametrize("username", ["", "a", "  a  ", "x" * 51])
    def test_rejects_an_out_of_range_username(self, store, username):
        with pytest.raises(ValueError, match="Username must be between"):
            auth.register_user(username, "password123")

    def test_accepts_the_boundary_lengths(self, store):
        auth.register_user("ab", "password123")
        auth.register_user("x" * 50, "password123")

    @pytest.mark.parametrize("password", ["", "short"])
    def test_rejects_a_short_password(self, store, password):
        with pytest.raises(ValueError, match="at least"):
            auth.register_user("alice", password)

    def test_accepts_a_six_character_password(self, store):
        auth.register_user("alice", "123456")

    def test_accepts_a_password_at_the_byte_limit(self, store):
        auth.register_user("alice", "x" * 72)

    def test_rejects_a_password_over_the_byte_limit(self, store):
        # Left through, bcrypt would raise and the account would be created with
        # no usable hash -- or worse, silently truncated to 72 bytes.
        with pytest.raises(ValueError, match="at most 72 bytes"):
            auth.register_user("alice", "x" * 73)
        assert store.user_count() == 0

    def test_the_limit_counts_utf8_bytes_not_characters(self, store):
        # 25 Chinese characters is 75 bytes.
        with pytest.raises(ValueError, match="at most 72 bytes"):
            auth.register_user("alice", "密" * 25)
        auth.register_user("alice", "密" * 24)

    def test_duplicate_username_is_rejected(self, registered):
        with pytest.raises(ValueError, match="already exists"):
            auth.register_user("alice", "password123")

    def test_a_failed_registration_creates_nothing(self, store):
        with pytest.raises(ValueError):
            auth.register_user("a", "password123")
        assert store.user_count() == 0


class TestAuthenticate:
    def test_correct_credentials(self, registered):
        assert auth.authenticate_user("alice", "password123") == {
            "username": "alice", "role": "user",
        }

    def test_wrong_password(self, registered):
        assert auth.authenticate_user("alice", "wrong") is None

    def test_unknown_user(self, registered):
        assert auth.authenticate_user("nobody", "password123") is None

    def test_case_sensitive_username(self, registered):
        assert auth.authenticate_user("Alice", "password123") is None


class TestInitAdmin:
    def test_creates_the_admin_account(self, store):
        auth.init_admin()
        row = store.get_user(settings.ADMIN_USERNAME)
        assert row["role"] == "admin"
        assert auth.verify_password(settings.ADMIN_PASSWORD, row["hashed_password"])

    def test_is_idempotent(self, store):
        auth.init_admin()
        auth.init_admin()
        assert store.user_count() == 1

    def test_does_not_reset_a_changed_password(self, store):
        # The shared database may already hold an admin created by the Spring
        # backend, or one whose password was rotated. Overwriting it on every
        # startup would silently undo that.
        store.create_user(settings.ADMIN_USERNAME, auth.hash_password("rotated"), "admin")
        auth.init_admin()
        assert auth.authenticate_user(settings.ADMIN_USERNAME, "rotated") is not None


class TestTokens:
    def test_round_trip(self):
        token = auth.create_access_token("alice", "user")
        assert auth._decode(token) == {"username": "alice", "role": "user"}

    def test_role_is_carried(self):
        assert auth._decode(auth.create_access_token("root", "admin"))["role"] == "admin"

    def test_claims_are_the_ones_the_spring_backend_reads(self):
        token = auth.create_access_token("alice", "user")
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=[auth.ALGORITHM])
        assert payload["sub"] == "alice"
        assert payload["role"] == "user"
        assert isinstance(payload["exp"], int)

    def test_algorithm_is_hs256(self):
        # Both backends must agree, and `none` must never be accepted.
        assert auth.ALGORITHM == "HS256"

    def test_expiry_follows_the_setting(self):
        token = auth.create_access_token("alice", "user")
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=[auth.ALGORITHM])
        expected = datetime.now(timezone.utc) + timedelta(
            hours=settings.ACCESS_TOKEN_EXPIRE_HOURS
        )
        actual = datetime.fromtimestamp(payload["exp"], tz=timezone.utc)
        assert abs(actual - expected) < timedelta(minutes=1)

    def test_garbage_is_rejected(self):
        assert auth._decode("not.a.token") is None

    def test_a_tampered_token_is_rejected(self):
        token = auth.create_access_token("alice", "user")
        head, payload, signature = token.split(".")
        assert auth._decode("%s.%s.%s" % (head, payload, signature[::-1])) is None

    def test_a_token_signed_with_another_secret_is_rejected(self):
        """The whole point of sharing JWT_SECRET between the backends."""
        foreign = jwt.encode(
            {"sub": "alice", "role": "admin",
             "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
            "a-completely-different-secret-value-here",
            algorithm=auth.ALGORITHM,
        )
        assert auth._decode(foreign) is None

    def test_an_expired_token_is_rejected(self):
        expired = jwt.encode(
            {"sub": "alice", "role": "user",
             "exp": datetime.now(timezone.utc) - timedelta(seconds=1)},
            settings.JWT_SECRET,
            algorithm=auth.ALGORITHM,
        )
        assert auth._decode(expired) is None

    def test_a_token_without_a_subject_is_rejected(self):
        anonymous = jwt.encode(
            {"role": "admin", "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
            settings.JWT_SECRET,
            algorithm=auth.ALGORITHM,
        )
        assert auth._decode(anonymous) is None

    def test_a_token_without_a_role_defaults_to_user(self):
        # Defaulting the other way would hand out admin to any old token.
        minimal = jwt.encode(
            {"sub": "alice", "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
            settings.JWT_SECRET,
            algorithm=auth.ALGORITHM,
        )
        assert auth._decode(minimal) == {"username": "alice", "role": "user"}


class Credentials:
    """Stand-in for fastapi.security's HTTPAuthorizationCredentials."""

    def __init__(self, token):
        self.scheme = "Bearer"
        self.credentials = token


class TestDependencies:
    """The FastAPI dependencies, called directly rather than through a client.

    Both are `async def`, and pytest-asyncio is deliberately not a dependency, so
    each test drives the coroutine itself.
    """

    @staticmethod
    def run(coroutine):
        import asyncio

        loop = asyncio.new_event_loop()
        try:
            return loop.run_until_complete(coroutine)
        finally:
            loop.close()

    def test_current_user_from_a_valid_token(self):
        token = auth.create_access_token("alice", "user")
        user = self.run(auth.get_current_user(Credentials(token)))
        assert user == {"username": "alice", "role": "user"}

    def test_current_user_without_credentials_is_401(self):
        with pytest.raises(HTTPException) as excinfo:
            self.run(auth.get_current_user(None))
        assert excinfo.value.status_code == 401
        # The browser needs this header to know it should re-authenticate.
        assert excinfo.value.headers["WWW-Authenticate"] == "Bearer"

    def test_current_user_with_an_invalid_token_is_401(self):
        with pytest.raises(HTTPException) as excinfo:
            self.run(auth.get_current_user(Credentials("garbage")))
        assert excinfo.value.status_code == 401

    def test_optional_user_without_credentials_is_none(self):
        assert self.run(auth.get_optional_user(None)) is None

    def test_optional_user_with_a_bad_token_is_none_not_an_error(self):
        assert self.run(auth.get_optional_user(Credentials("garbage"))) is None

    def test_optional_user_with_a_good_token(self):
        token = auth.create_access_token("alice", "user")
        assert self.run(auth.get_optional_user(Credentials(token)))["username"] == "alice"


class TestRoleChecks:
    def test_is_admin(self):
        assert auth.is_admin({"role": "admin"})
        assert not auth.is_admin({"role": "user"})
        assert not auth.is_admin({})

    def test_require_admin_passes_for_an_admin(self):
        auth.require_admin({"username": "root", "role": "admin"})

    def test_require_admin_is_403_for_a_user(self):
        # 403, not 401: the caller is authenticated, just not allowed.
        with pytest.raises(HTTPException) as excinfo:
            auth.require_admin({"username": "alice", "role": "user"})
        assert excinfo.value.status_code == 403

    def test_require_admin_is_403_when_the_role_is_missing(self):
        with pytest.raises(HTTPException) as excinfo:
            auth.require_admin({"username": "alice"})
        assert excinfo.value.status_code == 403
