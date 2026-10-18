"""Tests for app.core.config, in particular startup_warnings().

The generated-secret flags are decided once at import time, so each case has to
load the module fresh under a different environment. `importlib.reload` is not an
option: `app.core.config` is already imported (conftest needs it) and reloading it
in place would rebind the `settings` singleton that every other module holds a
reference to. Instead each case loads the file under a throwaway module name via
`spec_from_file_location`, which leaves `sys.modules["app.core.config"]` alone.

That works because the module's only imports are `secrets` and
`pydantic_settings` — no `app.*` import to resolve.
"""

import importlib.util
import os

import pytest

_CONFIG_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "core", "config.py",
)

#: Every variable the module reads. Cleared before each load so a developer's own
#: shell environment cannot change the outcome.
_RELEVANT = (
    "JWT_SECRET",
    "ADMIN_USERNAME",
    "ADMIN_PASSWORD",
    "CORS_ORIGINS",
    "DB_PATH",
    "ACCESS_TOKEN_EXPIRE_HOURS",
    "LLM_PROVIDER",
    "MILVUS_HOST",
)


@pytest.fixture()
def load_config(monkeypatch, tmp_path):
    """Load app/core/config.py fresh with the given environment."""

    def load(**env):
        for name in _RELEVANT:
            monkeypatch.delenv(name, raising=False)
        # `env_file: .env` is relative to the working directory, so a developer
        # with langchain-rag/.env filled in would otherwise leak values in here.
        monkeypatch.chdir(tmp_path)
        for name, value in env.items():
            monkeypatch.setenv(name, value)

        spec = importlib.util.spec_from_file_location("_config_under_test", _CONFIG_PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    return load


LONG_SECRET = "x" * 32
SHORT_SECRET = "too-short"


class TestDefaults:
    def test_the_db_path_is_shared_with_the_spring_backend(self, load_config):
        # Relative to the backend directory, so both land on <project>/data/rag.db.
        assert load_config().settings.DB_PATH == "../data/rag.db"

    def test_the_token_lifetime_matches_the_spring_default(self, load_config):
        assert load_config().settings.ACCESS_TOKEN_EXPIRE_HOURS == 72

    def test_the_default_cors_origin_is_the_dev_frontend(self, load_config):
        assert load_config().settings.cors_origin_list() == ["http://localhost:3000"]

    def test_environment_variables_override_the_defaults(self, load_config):
        assert load_config(MILVUS_HOST="milvus").settings.MILVUS_HOST == "milvus"


class TestCorsOriginList:
    def test_a_single_origin(self, load_config):
        settings = load_config(CORS_ORIGINS="http://a.test").settings
        assert settings.cors_origin_list() == ["http://a.test"]

    def test_a_comma_separated_list_is_split_and_trimmed(self, load_config):
        settings = load_config(CORS_ORIGINS=" http://a.test , http://b.test ").settings
        assert settings.cors_origin_list() == ["http://a.test", "http://b.test"]

    def test_empty_entries_are_dropped(self, load_config):
        settings = load_config(CORS_ORIGINS="http://a.test,,  ,").settings
        assert settings.cors_origin_list() == ["http://a.test"]

    def test_an_empty_value_yields_no_origins(self, load_config):
        # No origin allowed is the safe reading of "nothing configured" — quietly
        # re-allowing the dev origin would be worse. RagProperties.corsOriginList
        # used to fall back to http://localhost:3000 here; it no longer does.
        assert load_config(CORS_ORIGINS="").settings.cors_origin_list() == []

    def test_a_wildcard_stays_a_single_entry(self, load_config):
        assert load_config(CORS_ORIGINS="*").settings.cors_origin_list() == ["*"]


class TestGeneratedSecrets:
    def test_an_unset_jwt_secret_is_generated(self, load_config):
        # A hardcoded fallback would let anyone forge tokens against a default
        # deployment, so there must be no fixed value to fall back to.
        module = load_config(ADMIN_PASSWORD="set")
        assert len(module.settings.JWT_SECRET) >= 32
        assert module._JWT_SECRET_GENERATED is True

    def test_two_loads_generate_different_secrets(self, load_config):
        first = load_config(ADMIN_PASSWORD="set").settings.JWT_SECRET
        second = load_config(ADMIN_PASSWORD="set").settings.JWT_SECRET
        assert first != second

    def test_a_generated_secret_is_long_enough_for_hs256(self, load_config):
        module = load_config()
        secret_bytes = len(module.settings.JWT_SECRET.encode("utf-8"))
        assert secret_bytes >= module.MIN_JWT_SECRET_BYTES

    def test_a_configured_secret_is_kept(self, load_config):
        module = load_config(JWT_SECRET=LONG_SECRET, ADMIN_PASSWORD="set")
        assert module.settings.JWT_SECRET == LONG_SECRET
        assert module._JWT_SECRET_GENERATED is False

    def test_an_unset_admin_password_is_generated(self, load_config):
        module = load_config(JWT_SECRET=LONG_SECRET)
        assert module.settings.ADMIN_PASSWORD
        assert module._ADMIN_PASSWORD_GENERATED is True

    def test_a_configured_admin_password_is_kept(self, load_config):
        module = load_config(JWT_SECRET=LONG_SECRET, ADMIN_PASSWORD="hunter2hunter2")
        assert module.settings.ADMIN_PASSWORD == "hunter2hunter2"
        assert module._ADMIN_PASSWORD_GENERATED is False


class TestStartupWarnings:
    def test_a_fully_configured_deployment_warns_about_nothing(self, load_config):
        module = load_config(
            JWT_SECRET=LONG_SECRET,
            ADMIN_PASSWORD="hunter2hunter2",
            CORS_ORIGINS="https://rag.example.com",
        )
        assert module.startup_warnings() == []

    def test_a_generated_jwt_secret_is_reported(self, load_config):
        module = load_config(ADMIN_PASSWORD="set")
        assert any("JWT_SECRET is not set" in w for w in module.startup_warnings())

    def test_a_generated_admin_password_is_printed_so_login_is_possible(
        self, load_config
    ):
        # It exists only in this process's memory, so if it is not logged nobody
        # can sign in at all.
        module = load_config(JWT_SECRET=LONG_SECRET)
        warning = next(w for w in module.startup_warnings() if "ADMIN_PASSWORD" in w)
        assert module.settings.ADMIN_PASSWORD in warning

    def test_a_short_configured_secret_is_reported(self, load_config):
        # python-jose signs a short key happily; jjwt on the Spring side refuses
        # one under 256 bits, so tokens issued here could not be verified there.
        module = load_config(JWT_SECRET=SHORT_SECRET, ADMIN_PASSWORD="set")
        assert any("shorter than" in w for w in module.startup_warnings())

    def test_the_short_secret_warning_still_fires_without_an_admin_password(
        self, load_config
    ):
        # Regression: this check used to be an `elif` chained to the generated
        # admin-password branch, so it was silent in exactly the common case of a
        # short JWT_SECRET and no ADMIN_PASSWORD.
        module = load_config(JWT_SECRET=SHORT_SECRET)
        warnings = module.startup_warnings()
        assert any("shorter than" in w for w in warnings)
        assert any("ADMIN_PASSWORD" in w for w in warnings)

    def test_a_secret_at_the_boundary_does_not_warn(self, load_config):
        module = load_config(JWT_SECRET="x" * 32, ADMIN_PASSWORD="set")
        assert module.startup_warnings() == []

    def test_one_byte_short_does_warn(self, load_config):
        module = load_config(JWT_SECRET="x" * 31, ADMIN_PASSWORD="set")
        assert any("shorter than" in w for w in module.startup_warnings())

    def test_the_length_is_counted_in_bytes_not_characters(self, load_config):
        # 16 Chinese characters is 48 UTF-8 bytes, and both backends sign over the
        # raw bytes, so this is a perfectly good HS256 key.
        module = load_config(JWT_SECRET="密" * 16, ADMIN_PASSWORD="set")
        assert module.startup_warnings() == []

    def test_a_generated_secret_never_triggers_the_length_warning(self, load_config):
        module = load_config()
        assert not any("shorter than" in w for w in module.startup_warnings())

    def test_a_wildcard_cors_origin_is_reported(self, load_config):
        # Browsers reject credentialed requests against `*`, so the frontend's
        # Authorization header would never arrive.
        module = load_config(
            JWT_SECRET=LONG_SECRET, ADMIN_PASSWORD="set", CORS_ORIGINS="*"
        )
        assert any("CORS_ORIGINS is '*'" in w for w in module.startup_warnings())

    def test_a_wildcard_among_real_origins_is_still_reported(self, load_config):
        module = load_config(
            JWT_SECRET=LONG_SECRET,
            ADMIN_PASSWORD="set",
            CORS_ORIGINS="http://localhost:3000,*",
        )
        assert any("CORS_ORIGINS is '*'" in w for w in module.startup_warnings())

    def test_an_empty_cors_origin_is_reported(self, load_config):
        # Nothing is allowed through, so the frontend cannot call the backend at
        # all — a silent 403 on every request without this.
        module = load_config(
            JWT_SECRET=LONG_SECRET, ADMIN_PASSWORD="set", CORS_ORIGINS=""
        )
        assert any("CORS_ORIGINS is empty" in w for w in module.startup_warnings())

    def test_an_unconfigured_deployment_reports_everything_at_once(self, load_config):
        module = load_config(CORS_ORIGINS="*")
        assert len(module.startup_warnings()) == 3
