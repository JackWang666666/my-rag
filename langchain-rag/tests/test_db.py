"""Tests for app.core.db: schema, connection setup and timestamp formats.

The timestamp helpers matter more than they look: the Spring backend parses these
strings with `Instant.parse()`, which only accepts an ISO-8601 instant with an
explicit offset. A value written in a different shape is not a cosmetic difference,
it is an exception on the other backend.
"""

from datetime import datetime, timedelta, timezone

import pytest

from app.core import db

ISO_Z = "%Y-%m-%dT%H:%M:%SZ"


class TestSchema:
    def test_all_shared_tables_exist(self, fresh_db):
        rows = fresh_db.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        names = {r["name"] for r in rows}
        assert {
            "users",
            "knowledge_bases",
            "documents",
            "chunks",
            "sessions",
            "messages",
            "feedback",
        } <= names

    def test_init_db_is_idempotent(self, fresh_db):
        # Every statement is CREATE ... IF NOT EXISTS, so startup on an existing
        # database must not fail.
        fresh_db.init_db()
        fresh_db.init_db()

    def test_wal_is_enabled(self, fresh_db):
        # Without WAL the two backends block each other: a writer would lock out
        # every reader on the shared file.
        mode = fresh_db.query_one("PRAGMA journal_mode")[0]
        assert mode.lower() == "wal"

    def test_foreign_keys_are_enforced(self, fresh_db):
        assert fresh_db.query_one("PRAGMA foreign_keys")[0] == 1

    def test_feedback_is_unique_per_message(self, fresh_db):
        rows = fresh_db.query("PRAGMA index_list(feedback)")
        assert any(r["unique"] for r in rows)


class TestUnicode:
    def test_chinese_text_round_trips(self, fresh_db):
        fresh_db.execute(
            "INSERT INTO users (username, hashed_password, role, created_at)"
            " VALUES (?, ?, ?, ?)",
            ("张三", "hash", "user", fresh_db.now_iso()),
        )
        row = fresh_db.query_one("SELECT * FROM users WHERE username = ?", ("张三",))
        assert row["username"] == "张三"


class TestNowIso:
    def test_format_is_utc_with_a_trailing_z(self):
        value = db.now_iso()
        assert value.endswith("Z")
        # Parsing with the exact format proves there is no sub-second part and no
        # numeric offset, either of which Instant.parse would accept but our own
        # equality checks would not.
        datetime.strptime(value, ISO_Z)

    def test_is_actually_utc(self):
        parsed = datetime.strptime(db.now_iso(), ISO_Z).replace(tzinfo=timezone.utc)
        assert abs(parsed - datetime.now(timezone.utc)) < timedelta(seconds=5)


class TestToIso:
    def test_naive_datetime_is_treated_as_utc(self):
        assert db.to_iso(datetime(2026, 1, 2, 3, 4, 5)) == "2026-01-02T03:04:05Z"

    def test_aware_datetime_is_converted_to_utc(self):
        offset = timezone(timedelta(hours=8))
        value = datetime(2026, 1, 2, 11, 4, 5, tzinfo=offset)
        assert db.to_iso(value) == "2026-01-02T03:04:05Z"

    def test_sub_second_precision_is_dropped(self):
        value = datetime(2026, 1, 2, 3, 4, 5, 987654, tzinfo=timezone.utc)
        assert db.to_iso(value) == "2026-01-02T03:04:05Z"


class TestParseIso:
    @pytest.mark.parametrize(
        "value",
        [
            "2026-01-02T03:04:05Z",  # what we write
            "2026-01-02T03:04:05+00:00",  # what java.time.Instant may write
            "2026-01-02 03:04:05",  # SQLite's own CURRENT_TIMESTAMP shape
            "2026-01-02T03:04:05.123456Z",  # sub-second precision
            "2026-01-02T03:04:05.123456",
        ],
    )
    def test_accepts_every_shape_either_backend_can_write(self, value):
        parsed = db.parse_iso(value)
        assert parsed.tzinfo is not None
        assert (parsed.year, parsed.month, parsed.day) == (2026, 1, 2)
        assert (parsed.hour, parsed.minute, parsed.second) == (3, 4, 5)

    def test_offset_is_preserved_as_an_instant(self):
        parsed = db.parse_iso("2026-01-02T11:04:05+08:00")
        assert parsed.astimezone(timezone.utc).hour == 3

    def test_datetime_passes_through(self):
        value = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)
        assert db.parse_iso(value) == value

    def test_naive_datetime_gains_utc(self):
        assert db.parse_iso(datetime(2026, 1, 2)).tzinfo is timezone.utc

    @pytest.mark.parametrize("value", ["", None, "not a date", "2026-13-45"])
    def test_garbage_falls_back_to_now_rather_than_raising(self, value):
        # These timestamps are only ever used for display and ordering, so a bad
        # row must not take down the whole listing endpoint.
        parsed = db.parse_iso(value)
        assert abs(parsed - datetime.now(timezone.utc)) < timedelta(seconds=5)

    def test_round_trips_with_now_iso(self):
        written = db.now_iso()
        assert db.to_iso(db.parse_iso(written)) == written
