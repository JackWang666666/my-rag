"""Tests for app.core.store.

Two groups here guard the fixes for reported problems rather than just describing
behaviour:

* `TestVisibility` / `TestSessionIsolation` — sessions and knowledge bases used to
  be listed without an owner filter, so any logged-in user could enumerate (and
  then read) every other user's conversations.
* `TestChunkSearch` — chunk search used to be pushed down to Milvus, whose `like`
  only does prefix matching, so searching for a word in the middle of a chunk
  returned nothing.
"""

import json

import pytest


@pytest.fixture()
def kb(store):
    """A document knowledge base owned by 'alice'."""
    return store.create_kb(
        store.new_id(), "KB", "desc", "document", "alice", False, 1536
    )


class TestIds:
    def test_id_is_32_hex_chars(self, store):
        value = store.new_id()
        assert len(value) == 32
        # The Milvus collection name is `kb_<id>`, and Milvus rejects dashes, so a
        # str(uuid4()) here would break collection creation.
        assert "-" not in value
        int(value, 16)

    def test_ids_are_unique(self, store):
        assert len({store.new_id() for _ in range(200)}) == 200


class TestUsers:
    def test_create_then_get(self, store):
        store.create_user("alice", "hash", role="user")
        row = store.get_user("alice")
        assert row["username"] == "alice"
        assert row["hashed_password"] == "hash"
        assert row["role"] == "user"

    def test_missing_user_is_none(self, store):
        assert store.get_user("nobody") is None

    def test_username_is_the_primary_key(self, store):
        import sqlite3

        store.create_user("alice", "hash")
        with pytest.raises(sqlite3.IntegrityError):
            store.create_user("alice", "other")

    def test_user_count(self, store):
        assert store.user_count() == 0
        store.create_user("alice", "h")
        store.create_user("bob", "h")
        assert store.user_count() == 2


class TestKnowledgeBases:
    def test_create_returns_the_persisted_row(self, store):
        kb_id = store.new_id()
        row = store.create_kb(kb_id, "Docs", "d", "book", "alice", True, 1024)
        assert row["id"] == kb_id
        assert row["name"] == "Docs"
        assert row["kb_type"] == "book"
        assert row["embedding_dim"] == 1024
        # SQLite has no boolean type; the column is INTEGER on both backends.
        assert row["is_global"] == 1
        assert row["created_at"].endswith("Z")

    def test_delete(self, store, kb):
        assert store.delete_kb(kb["id"]) is True
        assert store.get_kb(kb["id"]) is None

    def test_delete_of_a_missing_kb_reports_false(self, store):
        assert store.delete_kb("nope") is False

    def test_documents_cascade_on_delete(self, store, kb):
        store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        store.delete_kb(kb["id"])
        assert store.list_documents(kb["id"]) == []

    def test_document_counts_in_one_query(self, store, kb):
        other = store.create_kb(store.new_id(), "B", "", "document", "alice", False, 1536)
        store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        store.create_document(store.new_id(), kb["id"], "b.txt", ".txt")
        store.create_document(store.new_id(), other["id"], "c.txt", ".txt")
        assert store.kb_document_counts() == {kb["id"]: 2, other["id"]: 1}


class TestVisibility:
    @pytest.fixture()
    def kbs(self, store):
        return {
            "alice": store.create_kb(store.new_id(), "alice-kb", "", "document", "alice", False, 1536),
            "bob": store.create_kb(store.new_id(), "bob-kb", "", "document", "bob", False, 1536),
            "shared": store.create_kb(store.new_id(), "shared-kb", "", "document", "bob", True, 1536),
        }

    def test_user_sees_own_and_global(self, store, kbs):
        visible = {k["name"] for k in store.list_visible_kbs("alice", "user")}
        assert visible == {"alice-kb", "shared-kb"}

    def test_user_does_not_see_another_users_private_kb(self, store, kbs):
        visible = {k["name"] for k in store.list_visible_kbs("alice", "user")}
        assert "bob-kb" not in visible

    def test_admin_sees_everything(self, store, kbs):
        visible = {k["name"] for k in store.list_visible_kbs("root", "admin")}
        assert visible == {"alice-kb", "bob-kb", "shared-kb"}

    def test_global_ids_can_exclude_one(self, store, kbs):
        assert store.list_global_kb_ids() == [kbs["shared"]["id"]]
        assert store.list_global_kb_ids(exclude=kbs["shared"]["id"]) == []


class TestDocuments:
    def test_starts_in_processing_with_no_error(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.pdf", ".pdf", 1234)
        assert doc["status"] == "processing"
        assert doc["error"] is None
        assert doc["chunk_count"] == 0
        assert doc["file_size"] == 1234

    def test_mark_completed_sets_the_chunk_count(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.pdf", ".pdf")
        store.mark_document_completed(doc["id"], 7)
        row = store.get_document(doc["id"])
        assert (row["status"], row["chunk_count"], row["error"]) == ("completed", 7, None)

    def test_mark_failed_records_the_reason(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.pdf", ".pdf")
        store.mark_document_failed(doc["id"], "no API key")
        row = store.get_document(doc["id"])
        assert row["status"] == "failed"
        # The frontend renders this under the status badge; without it a failed
        # document is a red badge and no explanation.
        assert row["error"] == "no API key"

    def test_a_huge_error_is_truncated_rather_than_stored_whole(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.pdf", ".pdf")
        store.mark_document_failed(doc["id"], "x" * 5000)
        assert len(store.get_document(doc["id"])["error"]) == 1000

    def test_retry_after_failure_clears_the_error(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.pdf", ".pdf")
        store.mark_document_failed(doc["id"], "transient")
        store.mark_document_completed(doc["id"], 3)
        assert store.get_document(doc["id"])["error"] is None

    def test_delete_removes_the_chunks_too(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": 0, "text": "hello", "source": "a.txt"},
        ])
        assert store.count_chunks(kb["id"]) == 1
        assert store.delete_document(doc["id"]) is True
        assert store.count_chunks(kb["id"]) == 0


class TestChunks:
    @pytest.fixture()
    def doc(self, store, kb):
        return store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")

    def test_metadata_round_trips_as_a_dict(self, store, kb, doc):
        store.insert_chunks(kb["id"], doc["id"], [{
            "id": store.new_id(),
            "chunk_index": 0,
            "text": "t",
            "source": "a.txt",
            "metadata": {"page": 3, "语言": "中文"},
        }])
        items, _ = store.list_chunks(kb["id"])
        assert items[0]["metadata"] == {"page": 3, "语言": "中文"}

    def test_missing_metadata_becomes_an_empty_dict(self, store, kb, doc):
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": 0, "text": "t"},
        ])
        items, _ = store.list_chunks(kb["id"])
        assert items[0]["metadata"] == {}
        assert items[0]["source"] == ""

    def test_corrupt_metadata_json_does_not_break_the_listing(self, store, kb, doc, fresh_db):
        # A row written by hand, or by a future schema, must not 500 the endpoint.
        fresh_db.execute(
            "INSERT INTO chunks (id, kb_id, document_id, chunk_index, source, text,"
            " metadata, created_at) VALUES (?, ?, ?, 0, '', 't', 'not json', ?)",
            (store.new_id(), kb["id"], doc["id"], fresh_db.now_iso()),
        )
        items, _ = store.list_chunks(kb["id"])
        assert items[0]["metadata"] == {}

    def test_reinsert_replaces_rather_than_duplicates(self, store, kb, doc):
        chunk_id = store.new_id()
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": chunk_id, "chunk_index": 0, "text": "first"},
        ])
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": chunk_id, "chunk_index": 0, "text": "second"},
        ])
        items, total = store.list_chunks(kb["id"])
        assert total == 1
        assert items[0]["text"] == "second"

    def test_ordering_is_by_document_then_index(self, store, kb, doc):
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": i, "text": "chunk %d" % i}
            for i in (2, 0, 1)
        ])
        items, _ = store.list_chunks(kb["id"])
        assert [i["chunk_index"] for i in items] == [0, 1, 2]


class TestChunkSearch:
    @pytest.fixture()
    def populated(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        texts = [
            "the quick brown fox",
            "jumps over the lazy dog",
            "100% pure arabica",
            "snake_case identifier",
            "向量数据库检索",
        ]
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": i, "text": t, "source": "a.txt"}
            for i, t in enumerate(texts)
        ])
        return kb["id"]

    def test_finds_a_word_in_the_middle(self, store, populated):
        # This is the case Milvus' prefix-only `like` could not do.
        items, total = store.list_chunks(populated, search="brown")
        assert total == 1
        assert items[0]["text"] == "the quick brown fox"

    def test_search_matches_the_source_too(self, store, populated):
        _, total = store.list_chunks(populated, search="a.txt")
        assert total == 5

    def test_percent_in_the_query_is_a_literal_not_a_wildcard(self, store, populated):
        items, total = store.list_chunks(populated, search="100%")
        assert total == 1
        assert items[0]["text"] == "100% pure arabica"

    def test_underscore_in_the_query_is_a_literal_not_a_wildcard(self, store, populated):
        # Unescaped, `_` matches any single character, so "snake_case" would also
        # be found by "snakeXcase".
        assert store.list_chunks(populated, search="snake_case")[1] == 1
        assert store.list_chunks(populated, search="snakeXcase")[1] == 0

    def test_chinese_search(self, store, populated):
        assert store.list_chunks(populated, search="数据库")[1] == 1

    def test_no_match_returns_empty(self, store, populated):
        assert store.list_chunks(populated, search="nonexistent") == ([], 0)


class TestChunkPagination:
    @pytest.fixture()
    def populated(self, store, kb):
        doc = store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": i, "text": "chunk %d" % i}
            for i in range(25)
        ])
        return kb["id"]

    def test_total_is_the_unpaged_count(self, store, populated):
        items, total = store.list_chunks(populated, page=1, page_size=10)
        assert (len(items), total) == (10, 25)

    def test_second_page_continues_where_the_first_ended(self, store, populated):
        first, _ = store.list_chunks(populated, page=1, page_size=10)
        second, _ = store.list_chunks(populated, page=2, page_size=10)
        assert first[-1]["chunk_index"] == 9
        assert second[0]["chunk_index"] == 10

    def test_last_page_may_be_short(self, store, populated):
        items, _ = store.list_chunks(populated, page=3, page_size=10)
        assert len(items) == 5

    def test_page_beyond_the_end_is_empty_not_an_error(self, store, populated):
        assert store.list_chunks(populated, page=99, page_size=10)[0] == []

    def test_page_zero_is_clamped_to_the_first_page(self, store, populated):
        assert store.list_chunks(populated, page=0, page_size=10)[0][0]["chunk_index"] == 0

    def test_page_size_is_capped(self, store, populated):
        # An unbounded page_size lets one request pull an entire knowledge base
        # into memory.
        items, _ = store.list_chunks(populated, page=1, page_size=100000)
        assert len(items) == 25


class TestSessionIsolation:
    @pytest.fixture()
    def sessions(self, store, kb):
        return {
            "alice": store.create_session(store.new_id(), "alice chat", kb["id"], "alice"),
            "bob": store.create_session(store.new_id(), "bob chat", kb["id"], "bob"),
        }

    def test_user_sees_only_their_own(self, store, sessions):
        titles = {s["title"] for s in store.list_sessions("alice", "user")}
        assert titles == {"alice chat"}

    def test_admin_sees_all(self, store, sessions):
        titles = {s["title"] for s in store.list_sessions("root", "admin")}
        assert titles == {"alice chat", "bob chat"}

    def test_kb_filter_still_applies_the_owner_filter(self, store, kb, sessions):
        titles = {s["title"] for s in store.list_sessions("alice", "user", kb_id=kb["id"])}
        assert titles == {"alice chat"}

    def test_kb_filter_excludes_other_kbs(self, store, sessions):
        assert store.list_sessions("alice", "user", kb_id="other-kb") == []


class TestMessages:
    @pytest.fixture()
    def session(self, store, kb):
        return store.create_session(store.new_id(), "chat", kb["id"], "alice")

    def test_index_starts_at_zero_and_increments(self, store, session):
        assert store.append_message(session["id"], "user", "hi") == 0
        assert store.append_message(session["id"], "assistant", "hello") == 1
        assert store.append_message(session["id"], "user", "more") == 2

    def test_indexes_are_per_session(self, store, kb, session):
        other = store.create_session(store.new_id(), "other", kb["id"], "alice")
        store.append_message(session["id"], "user", "a")
        assert store.append_message(other["id"], "user", "a") == 0

    def test_sources_and_confidence_round_trip(self, store, session):
        sources = [{"text": "t", "score": 0.9, "source": "a.txt",
                    "documentId": "d1", "rerankScore": None}]
        store.append_message(session["id"], "assistant", "answer",
                             sources=sources, confidence=0.83, route="hybrid")
        message = store.list_messages(session["id"])[0]
        assert message["sources"] == sources
        assert message["confidence"] == pytest.approx(0.83)
        assert message["route"] == "hybrid"

    def test_user_messages_carry_no_sources(self, store, session):
        store.append_message(session["id"], "user", "hi")
        message = store.list_messages(session["id"])[0]
        assert message["sources"] is None
        assert message["confidence"] is None

    def test_corrupt_sources_json_does_not_break_history(self, store, session, fresh_db):
        fresh_db.execute(
            "INSERT INTO messages (id, session_id, message_index, role, content,"
            " sources, created_at) VALUES (?, ?, 0, 'assistant', 'a', 'oops', ?)",
            (store.new_id(), session["id"], fresh_db.now_iso()),
        )
        assert store.list_messages(session["id"])[0]["sources"] is None

    def test_history_is_oldest_first(self, store, session):
        for i in range(5):
            store.append_message(session["id"], "user", "m%d" % i)
        assert [m["content"] for m in store.list_messages(session["id"])] == \
            ["m0", "m1", "m2", "m3", "m4"]

    def test_limit_keeps_the_most_recent_in_order(self, store, session):
        for i in range(5):
            store.append_message(session["id"], "user", "m%d" % i)
        assert [m["content"] for m in store.list_messages(session["id"], limit=2)] == \
            ["m3", "m4"]

    def test_appending_touches_the_session(self, store, kb, session):
        store.rename_session(session["id"], "renamed")
        before = store.get_session(session["id"])["updated_at"]
        store.append_message(session["id"], "user", "hi")
        assert store.get_session(session["id"])["updated_at"] >= before

    def test_messages_cascade_when_the_session_goes(self, store, session):
        store.append_message(session["id"], "user", "hi")
        store.delete_session(session["id"])
        assert store.list_messages(session["id"]) == []

    def test_message_counts_in_one_query(self, store, kb, session):
        other = store.create_session(store.new_id(), "other", kb["id"], "alice")
        store.append_message(session["id"], "user", "a")
        store.append_message(session["id"], "user", "b")
        store.append_message(other["id"], "user", "c")
        assert store.session_message_counts() == {session["id"]: 2, other["id"]: 1}


class TestFeedback:
    @pytest.fixture()
    def session(self, store, kb):
        session = store.create_session(store.new_id(), "chat", kb["id"], "alice")
        store.append_message(session["id"], "user", "q")
        store.append_message(session["id"], "assistant", "a")
        return session

    def test_first_vote_is_recorded(self, store, session, fresh_db):
        store.upsert_feedback(session["id"], 1, "alice", "up")
        rows = fresh_db.query("SELECT * FROM feedback")
        assert len(rows) == 1
        assert rows[0]["rating"] == "up"

    def test_changing_a_vote_replaces_the_row(self, store, session, fresh_db):
        store.upsert_feedback(session["id"], 1, "alice", "up")
        store.upsert_feedback(session["id"], 1, "alice", "down")
        rows = fresh_db.query("SELECT * FROM feedback")
        assert len(rows) == 1
        assert rows[0]["rating"] == "down"

    def test_adding_a_comment_later_keeps_one_row(self, store, session, fresh_db):
        # The UI submits the rating on click, then the comment when "补充说明" is
        # used. Two rows here would double-count the vote in the stats.
        store.upsert_feedback(session["id"], 1, "alice", "up")
        store.upsert_feedback(session["id"], 1, "alice", "up", comment="helpful")
        rows = fresh_db.query("SELECT * FROM feedback")
        assert len(rows) == 1
        assert rows[0]["comment"] == "helpful"

    def test_different_messages_are_separate_rows(self, store, session, fresh_db):
        store.upsert_feedback(session["id"], 1, "alice", "up")
        store.upsert_feedback(session["id"], 3, "alice", "down")
        assert len(fresh_db.query("SELECT * FROM feedback")) == 2

    def test_stats_are_scoped_to_the_owner(self, store, kb, session):
        bob = store.create_session(store.new_id(), "bob", kb["id"], "bob")
        store.upsert_feedback(session["id"], 1, "alice", "up")
        store.upsert_feedback(bob["id"], 1, "bob", "down")

        assert store.feedback_stats("alice") == {
            "total": 1, "positive": 1, "negative": 0, "satisfaction_rate": 1.0,
        }
        assert store.feedback_stats(None)["total"] == 2

    def test_satisfaction_rate_is_rounded(self, store, kb):
        for i, rating in enumerate(["up", "up", "down"]):
            session = store.create_session(store.new_id(), "s%d" % i, kb["id"], "alice")
            store.upsert_feedback(session["id"], 0, "alice", rating)
        assert store.feedback_stats("alice")["satisfaction_rate"] == pytest.approx(0.6667)

    def test_no_feedback_gives_a_zero_rate_not_a_division_error(self, store):
        assert store.feedback_stats("alice") == {
            "total": 0, "positive": 0, "negative": 0, "satisfaction_rate": 0.0,
        }

    def test_feedback_goes_when_the_session_does(self, store, session, fresh_db):
        store.upsert_feedback(session["id"], 1, "alice", "up")
        store.delete_session(session["id"])
        # feedback has no FK to sessions (the Spring backend writes it too), so
        # delete_session removes the rows explicitly.
        assert fresh_db.query("SELECT * FROM feedback") == []


class TestJsonEncoding:
    def test_non_ascii_is_stored_unescaped(self, store, kb, fresh_db):
        """`ensure_ascii=False` keeps the column readable and halves its size."""
        doc = store.create_document(store.new_id(), kb["id"], "a.txt", ".txt")
        store.insert_chunks(kb["id"], doc["id"], [
            {"id": store.new_id(), "chunk_index": 0, "text": "t",
             "metadata": {"title": "中文"}},
        ])
        raw = fresh_db.query_one("SELECT metadata FROM chunks")["metadata"]
        assert "中文" in raw
        assert "\\u" not in raw
        assert json.loads(raw) == {"title": "中文"}
