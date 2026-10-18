package com.rag.store;

import com.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirror of {@code langchain-rag/tests/test_store.py}.
 *
 * <p>Runs against a real SQLite file in a temp directory rather than a mock: the
 * behaviour worth testing here *is* the SQL — the owner filter that stops one user
 * reading another's conversations, the infix {@code LIKE} that Milvus cannot do,
 * and the feedback upsert that must not double-count a vote.
 */
class MetadataStoreTest {

    private MetadataStore store;
    private Db db;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        RagProperties props = new RagProperties();
        props.setDbPath(dir.resolve("rag.db").toString());
        db = new Db(props);
        db.init();
        store = new MetadataStore(db);
    }

    /**
     * Raw SQL, for the handful of cases the store's own API cannot set up: forcing a
     * timestamp (the format has one-second resolution, so two calls in the same test
     * usually land in the same second) and deleting a message (no such method, by
     * design — but the index sequencing has to survive one happening).
     */
    private void sql(String statement) {
        try (Connection conn = db.open(); Statement st = conn.createStatement()) {
            st.executeUpdate(statement);
        } catch (Exception e) {
            throw new IllegalStateException(statement, e);
        }
    }

    private String kb(String owner) {
        return kb(owner, false);
    }

    private String kb(String owner, boolean global) {
        String id = MetadataStore.newId();
        store.createKb(id, "知识库", "", "document", owner, global, 1536);
        return id;
    }

    @Nested
    class NewId {

        @Test
        void isThirtyTwoLowercaseHexCharacters() {
            // The Milvus collection name is derived from it as `kb_<id>`, and Milvus
            // rejects dashes in collection names.
            assertThat(MetadataStore.newId()).matches("[0-9a-f]{32}");
        }

        @Test
        void isUnique() {
            assertThat(MetadataStore.newId()).isNotEqualTo(MetadataStore.newId());
        }
    }

    @Nested
    class Users {

        @Test
        void createAndRead() {
            MetadataStore.UserRow created = store.createUser("alice", "hash", "user");
            assertThat(created.username()).isEqualTo("alice");
            assertThat(store.getUser("alice").hashedPassword()).isEqualTo("hash");
        }

        @Test
        void anUnknownUserIsNull() {
            assertThat(store.getUser("nobody")).isNull();
        }

        @Test
        void lookupIsCaseSensitive() {
            store.createUser("alice", "hash", "user");
            assertThat(store.getUser("Alice")).isNull();
        }

        @Test
        void aDuplicateUsernameIsRejectedByThePrimaryKey() {
            store.createUser("alice", "hash", "user");
            assertThatThrownBy(() -> store.createUser("alice", "other", "user"))
                    .isInstanceOf(MetadataStore.StoreException.class);
        }

        @Test
        void userCount() {
            assertThat(store.userCount()).isZero();
            store.createUser("alice", "hash", "user");
            store.createUser("bob", "hash", "user");
            assertThat(store.userCount()).isEqualTo(2);
        }

        @Test
        void aCreatedAtIsAlwaysWritten() {
            // Not nullable, and the Python backend parses it, so a missing value
            // would break the other backend's listing rather than this one's.
            assertThat(store.createUser("alice", "hash", "user").createdAt()).isNotBlank();
        }

        @Test
        void unicodeUsernamesRoundTrip() {
            store.createUser("张三", "hash", "user");
            assertThat(store.getUser("张三")).isNotNull();
        }
    }

    @Nested
    class KnowledgeBases {

        @Test
        void createAndRead() {
            String id = MetadataStore.newId();
            MetadataStore.KbRow row = store.createKb(id, "手册", "说明", "book", "alice", false, 1024);
            assertThat(row.name()).isEqualTo("手册");
            assertThat(row.description()).isEqualTo("说明");
            assertThat(row.kbType()).isEqualTo("book");
            assertThat(row.owner()).isEqualTo("alice");
            assertThat(row.isGlobal()).isFalse();
            assertThat(row.embeddingDim()).isEqualTo(1024);
        }

        @Test
        void theGlobalFlagRoundTripsAsAnInteger() {
            // SQLite has no boolean type; the column is INTEGER and the Python
            // backend writes 0/1 into it too.
            String id = kb("alice", true);
            assertThat(store.getKb(id).isGlobal()).isTrue();
        }

        @Test
        void anUnknownKbIsNull() {
            assertThat(store.getKb("nope")).isNull();
        }

        @Test
        void deleteReportsWhetherAnythingWasRemoved() {
            String id = kb("alice");
            assertThat(store.deleteKb(id)).isTrue();
            assertThat(store.deleteKb(id)).isFalse();
        }

        @Test
        void deletingAKbCascadesToDocumentsAndChunks() {
            String kbId = kb("alice");
            store.createDocument("doc1", kbId, "a.txt", "txt", 10, "completed");
            store.insertChunks(kbId, "doc1", List.of(
                    new MetadataStore.ChunkInput("c1", 0, "a.txt", "text", Map.of())));

            store.deleteKb(kbId);

            assertThat(store.getDocument("doc1")).isNull();
            assertThat(store.countChunks(kbId)).isZero();
        }

        @Test
        void documentCountsComeBackInOneQuery() {
            String first = kb("alice");
            String second = kb("alice");
            store.createDocument("d1", first, "a.txt", "txt", 1, "completed");
            store.createDocument("d2", first, "b.txt", "txt", 1, "completed");
            store.createDocument("d3", second, "c.txt", "txt", 1, "completed");

            assertThat(store.kbDocumentCounts()).containsEntry(first, 2).containsEntry(second, 1);
            assertThat(store.kbDocumentCount(first)).isEqualTo(2);
        }

        @Test
        void aKbWithNoDocumentsIsAbsentFromTheCountsMap() {
            // The caller has to treat "absent" as zero; GROUP BY produces no row.
            String id = kb("alice");
            assertThat(store.kbDocumentCounts()).doesNotContainKey(id);
            assertThat(store.kbDocumentCount(id)).isZero();
        }
    }

    @Nested
    @DisplayName("knowledge base visibility")
    class Visibility {

        @Test
        void aUserSeesTheirOwnAndTheGlobalOnes() {
            String mine = kb("alice");
            String global = kb("bob", true);
            String theirs = kb("bob");

            List<String> visible = store.listVisibleKbs("alice", "user").stream()
                    .map(MetadataStore.KbRow::id).toList();
            assertThat(visible).contains(mine, global).doesNotContain(theirs);
        }

        @Test
        void anAdminSeesEverything() {
            String mine = kb("alice");
            String theirs = kb("bob");
            List<String> visible = store.listVisibleKbs("alice", "admin").stream()
                    .map(MetadataStore.KbRow::id).toList();
            assertThat(visible).contains(mine, theirs);
        }

        @Test
        void globalIdsExcludeTheOneAsked() {
            String first = kb("alice", true);
            String second = kb("bob", true);
            kb("alice", false);
            assertThat(store.listGlobalKbIds(first)).containsExactly(second);
        }

        @Test
        void globalIdsWithNothingToExclude() {
            String only = kb("alice", true);
            assertThat(store.listGlobalKbIds(null)).containsExactly(only);
        }

        @Test
        void globalRowsCarryTheNameTheRouterNeeds() {
            // listGlobalKbIds used to select whole rows and keep only the ids, so the
            // router had no way to know a global KB might cover the question and
            // routed `direct` — skipping retrieval entirely.
            String id = MetadataStore.newId();
            store.createKb(id, "天龙八部", "金庸小说全文", "book", "alice", true, 1024);

            assertThat(store.listGlobalKbs(null))
                    .singleElement()
                    .satisfies(kb -> {
                        assertThat(kb.id()).isEqualTo(id);
                        assertThat(kb.name()).isEqualTo("天龙八部");
                        assertThat(kb.description()).isEqualTo("金庸小说全文");
                    });
        }

        @Test
        void globalRowsAndIdsAgree() {
            kb("alice", true);
            kb("bob", true);
            String privateKb = kb("alice", false);

            assertThat(store.listGlobalKbs(privateKb).stream()
                    .map(MetadataStore.KbRow::id).toList())
                    .isEqualTo(store.listGlobalKbIds(privateKb));
        }
    }

    @Nested
    class Documents {

        @Test
        void createStartsAtZeroChunksWithNoError() {
            String kbId = kb("alice");
            MetadataStore.DocRow row =
                    store.createDocument("d1", kbId, "报告.pdf", "pdf", 2048, "processing");
            assertThat(row.filename()).isEqualTo("报告.pdf");
            assertThat(row.fileSize()).isEqualTo(2048);
            assertThat(row.status()).isEqualTo("processing");
            assertThat(row.chunkCount()).isZero();
            assertThat(row.error()).isNull();
        }

        @Test
        void markCompletedSetsTheChunkCountAndClearsAnyError() {
            String kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "processing");
            store.markDocumentFailed("d1", "boom");
            store.markDocumentCompleted("d1", 42);

            MetadataStore.DocRow row = store.getDocument("d1");
            assertThat(row.status()).isEqualTo("completed");
            assertThat(row.chunkCount()).isEqualTo(42);
            assertThat(row.error()).isNull();
        }

        @Test
        void markFailedRecordsTheMessage() {
            String kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "processing");
            store.markDocumentFailed("d1", "unsupported file type");

            MetadataStore.DocRow row = store.getDocument("d1");
            assertThat(row.status()).isEqualTo("failed");
            assertThat(row.error()).isEqualTo("unsupported file type");
        }

        @Test
        void aLongErrorIsTruncated() {
            // Stack traces arrive here; the column is displayed in the UI and the
            // whole row is returned by the list endpoint.
            String kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "processing");
            store.markDocumentFailed("d1", "x".repeat(5000));
            assertThat(store.getDocument("d1").error()).hasSize(1000);
        }

        @Test
        void aNullErrorBecomesAPlaceholder() {
            String kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "processing");
            store.markDocumentFailed("d1", null);
            assertThat(store.getDocument("d1").error()).isEqualTo("unknown error");
        }

        @Test
        void deleteRemovesTheChunksToo() {
            String kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "completed");
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c1", 0, "a.txt", "text", Map.of())));

            assertThat(store.deleteDocument("d1")).isTrue();
            assertThat(store.countChunks(kbId)).isZero();
            assertThat(store.deleteDocument("d1")).isFalse();
        }

        @Test
        void listIsScopedToTheKb() {
            String mine = kb("alice");
            String other = kb("alice");
            store.createDocument("d1", mine, "a.txt", "txt", 1, "completed");
            store.createDocument("d2", other, "b.txt", "txt", 1, "completed");
            assertThat(store.listDocuments(mine)).extracting(MetadataStore.DocRow::id)
                    .containsExactly("d1");
        }

        @Test
        void aDocumentInAMissingKbIsRejected() {
            // The foreign key is what keeps orphaned documents out of the listing.
            assertThatThrownBy(() -> store.createDocument("d1", "no-such-kb", "a.txt", "txt", 1,
                    "processing")).isInstanceOf(MetadataStore.StoreException.class);
        }
    }

    @Nested
    class Chunks {

        private String kbId;

        @BeforeEach
        void insert() {
            kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "completed");
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c0", 0, "a.txt",
                            "the quick brown fox", Map.of("page", 1.0)),
                    new MetadataStore.ChunkInput("c1", 1, "a.txt",
                            "jumps over the lazy dog", Map.of()),
                    new MetadataStore.ChunkInput("c2", 2, "b.txt",
                            "snake_case and 100% coverage", Map.of())));
        }

        @Test
        void insertingNothingIsANoOp() {
            store.insertChunks(kbId, "d1", List.of());
            assertThat(store.countChunks(kbId)).isEqualTo(3);
        }

        @Test
        void metadataRoundTripsAsJson() {
            MetadataStore.ChunkRow row = store.listChunks(kbId, 1, 10, null).chunks().get(0);
            assertThat(row.metadata()).containsEntry("page", 1.0);
        }

        @Test
        void anAbsentMetadataMapBecomesAnEmptyOne() {
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c9", 9, "a.txt", "text", null)));
            MetadataStore.ChunkRow row = store.listChunks(kbId, 1, 100, "text").chunks().get(0);
            assertThat(row.metadata()).isEmpty();
        }

        @Test
        void reinsertingTheSameIdReplacesRatherThanDuplicates() {
            // Re-ingesting a document must not double every chunk.
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c0", 0, "a.txt", "rewritten", Map.of())));
            assertThat(store.countChunks(kbId)).isEqualTo(3);
        }

        @Test
        void orderedByDocumentThenIndex() {
            assertThat(store.listChunks(kbId, 1, 10, null).chunks())
                    .extracting(MetadataStore.ChunkRow::chunkIndex)
                    .containsExactly(0, 1, 2);
        }

        @Test
        void paginationSplitsTheRows() {
            MetadataStore.ChunkPage first = store.listChunks(kbId, 1, 2, null);
            assertThat(first.chunks()).hasSize(2);
            assertThat(first.total()).isEqualTo(3);

            MetadataStore.ChunkPage second = store.listChunks(kbId, 2, 2, null);
            assertThat(second.chunks()).hasSize(1);
            assertThat(second.total()).isEqualTo(3);
        }

        @Test
        void aPageBeyondTheEndIsEmptyButStillReportsTheTotal() {
            MetadataStore.ChunkPage page = store.listChunks(kbId, 99, 10, null);
            assertThat(page.chunks()).isEmpty();
            assertThat(page.total()).isEqualTo(3);
        }

        @Test
        void aNonPositivePageIsTreatedAsTheFirst() {
            assertThat(store.listChunks(kbId, 0, 10, null).chunks()).hasSize(3);
            assertThat(store.listChunks(kbId, -5, 10, null).chunks()).hasSize(3);
        }

        @Test
        void thePageSizeIsCapped() {
            // Without the cap a client could ask for every chunk of a large corpus in
            // one response.
            List<MetadataStore.ChunkInput> many = new ArrayList<>();
            for (int i = 100; i < 400; i++) {
                many.add(new MetadataStore.ChunkInput("c" + i, i, "a.txt", "text", Map.of()));
            }
            store.insertChunks(kbId, "d1", many);

            MetadataStore.ChunkPage page = store.listChunks(kbId, 1, 100_000, null);
            assertThat(page.chunks()).hasSize(200);
            assertThat(page.total()).isEqualTo(303);
        }

        @Test
        void aNonPositivePageSizeReturnsOneRow() {
            assertThat(store.listChunks(kbId, 1, 0, null).chunks()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("chunk search: what Milvus' prefix-only `like` could not do")
    class ChunkSearch {

        private String kbId;

        @BeforeEach
        void insert() {
            kbId = kb("alice");
            store.createDocument("d1", kbId, "a.txt", "txt", 1, "completed");
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c0", 0, "a.txt",
                            "the quick brown fox", Map.of()),
                    new MetadataStore.ChunkInput("c1", 1, "报告.pdf",
                            "jumps over the lazy dog", Map.of()),
                    new MetadataStore.ChunkInput("c2", 2, "b.txt",
                            "snake_case and 100% coverage", Map.of())));
        }

        @Test
        void findsAWordInTheMiddle() {
            assertThat(store.listChunks(kbId, 1, 10, "brown").total()).isEqualTo(1);
        }

        @Test
        void findsAPrefixToo() {
            assertThat(store.listChunks(kbId, 1, 10, "the").total()).isEqualTo(2);
        }

        @Test
        void searchesTheSourceAsWellAsTheText() {
            assertThat(store.listChunks(kbId, 1, 10, "报告").total()).isEqualTo(1);
        }

        @Test
        void anEmptySearchIsNoFilter() {
            assertThat(store.listChunks(kbId, 1, 10, "").total()).isEqualTo(3);
            assertThat(store.listChunks(kbId, 1, 10, null).total()).isEqualTo(3);
        }

        @Test
        void noMatchIsAnEmptyPage() {
            MetadataStore.ChunkPage page = store.listChunks(kbId, 1, 10, "nothing here");
            assertThat(page.chunks()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        void underscoreInTheQueryIsALiteralNotAWildcard() {
            // Unescaped, LIKE '%snakeXcase%' would match "snake_case".
            assertThat(store.listChunks(kbId, 1, 10, "snake_case").total()).isEqualTo(1);
            assertThat(store.listChunks(kbId, 1, 10, "snakeXcase").total()).isZero();
        }

        @Test
        void percentInTheQueryIsALiteralNotAWildcard() {
            assertThat(store.listChunks(kbId, 1, 10, "100%").total()).isEqualTo(1);
            assertThat(store.listChunks(kbId, 1, 10, "100%zzz").total()).isZero();
        }

        @Test
        void aBareWildcardMatchesNothing() {
            // Escaped, `%` is just a character no chunk's text contains twice over.
            assertThat(store.listChunks(kbId, 1, 10, "%%%").total()).isZero();
        }

        @Test
        void aBackslashInTheQueryIsALiteral() {
            store.insertChunks(kbId, "d1", List.of(
                    new MetadataStore.ChunkInput("c3", 3, "a.txt", "C:\\Users\\path", Map.of())));
            assertThat(store.listChunks(kbId, 1, 10, "C:\\Users").total()).isEqualTo(1);
        }

        @Test
        void aQuoteInTheQueryIsNotAnInjection() {
            // Parameter-bound, so this is a search for a literal string and the rows
            // are still all there afterwards.
            assertThat(store.listChunks(kbId, 1, 10, "' OR 1=1 --").total()).isZero();
            assertThat(store.listChunks(kbId, 1, 10, "'; DROP TABLE chunks; --").total()).isZero();
            assertThat(store.countChunks(kbId)).isEqualTo(3);
        }

        @Test
        void searchIsScopedToTheKb() {
            String other = kb("bob");
            store.createDocument("d2", other, "c.txt", "txt", 1, "completed");
            store.insertChunks(other, "d2", List.of(
                    new MetadataStore.ChunkInput("c9", 0, "c.txt", "brown", Map.of())));
            assertThat(store.listChunks(kbId, 1, 10, "brown").total()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("session isolation")
    class Sessions {

        @Test
        void createAndRead() {
            String kbId = kb("alice");
            MetadataStore.SessionRow row = store.createSession("s1", "第一次对话", kbId, "alice");
            assertThat(row.title()).isEqualTo("第一次对话");
            assertThat(row.owner()).isEqualTo("alice");
            assertThat(store.getSession("s1").kbId()).isEqualTo(kbId);
        }

        @Test
        void anUnknownSessionIsNull() {
            assertThat(store.getSession("nope")).isNull();
        }

        @Test
        void aUserOnlySeesTheirOwnSessions() {
            // Without the owner filter any logged-in user could enumerate — and then
            // read — every other user's conversations.
            String kbId = kb("alice", true);
            store.createSession("mine", "t", kbId, "alice");
            store.createSession("theirs", "t", kbId, "bob");

            assertThat(store.listSessions("alice", "user", null))
                    .extracting(MetadataStore.SessionRow::id).containsExactly("mine");
        }

        @Test
        void anAdminSeesEveryone() {
            String kbId = kb("alice", true);
            store.createSession("mine", "t", kbId, "alice");
            store.createSession("theirs", "t", kbId, "bob");

            assertThat(store.listSessions("alice", "admin", null))
                    .extracting(MetadataStore.SessionRow::id)
                    .containsExactlyInAnyOrder("mine", "theirs");
        }

        @Test
        void theKbFilterAppliesOnTopOfTheOwnerFilter() {
            String first = kb("alice");
            String second = kb("alice");
            store.createSession("a", "t", first, "alice");
            store.createSession("b", "t", second, "alice");
            store.createSession("c", "t", first, "bob");

            assertThat(store.listSessions("alice", "user", first))
                    .extracting(MetadataStore.SessionRow::id).containsExactly("a");
        }

        @Test
        void theKbFilterAlsoAppliesForAnAdmin() {
            String first = kb("alice");
            String second = kb("alice");
            store.createSession("a", "t", first, "alice");
            store.createSession("b", "t", second, "bob");

            assertThat(store.listSessions("alice", "admin", first))
                    .extracting(MetadataStore.SessionRow::id).containsExactly("a");
        }

        @Test
        void renameChangesOnlyTheTitle() {
            String kbId = kb("alice");
            store.createSession("s1", "旧标题", kbId, "alice");
            store.renameSession("s1", "新标题");
            assertThat(store.getSession("s1").title()).isEqualTo("新标题");
            assertThat(store.getSession("s1").owner()).isEqualTo("alice");
        }

        @Test
        void deleteReportsWhetherAnythingWasRemoved() {
            String kbId = kb("alice");
            store.createSession("s1", "t", kbId, "alice");
            assertThat(store.deleteSession("s1")).isTrue();
            assertThat(store.deleteSession("s1")).isFalse();
        }

        @Test
        void deletingASessionRemovesItsMessagesAndFeedback() {
            String kbId = kb("alice");
            store.createSession("s1", "t", kbId, "alice");
            int index = store.appendMessage("s1", "assistant", "答案", null, 0.9, "rag", null);
            store.upsertFeedback("s1", index, "alice", "up", null);

            store.deleteSession("s1");

            assertThat(store.sessionMessageCount("s1")).isZero();
            assertThat(store.feedbackStats(null).total()).isZero();
        }

        @Test
        void listedNewestFirst() {
            String kbId = kb("alice");
            store.createSession("older", "t", kbId, "alice");
            store.createSession("newer", "t", kbId, "alice");
            sql("UPDATE sessions SET updated_at = '2020-01-01T00:00:00Z' WHERE id = 'older'");

            assertThat(store.listSessions("alice", "user", null))
                    .extracting(MetadataStore.SessionRow::id).containsExactly("newer", "older");
        }

        @Test
        void touchMovesASessionToTheTopOfTheList() {
            // The sidebar is ordered by updated_at, so a session that just received a
            // message has to come first.
            String kbId = kb("alice");
            store.createSession("stale", "t", kbId, "alice");
            store.createSession("other", "t", kbId, "alice");
            sql("UPDATE sessions SET updated_at = '2020-01-01T00:00:00Z' WHERE id = 'stale'");

            store.touchSession("stale");

            assertThat(store.listSessions("alice", "user", null))
                    .extracting(MetadataStore.SessionRow::id).containsExactly("stale", "other");
        }

        @Test
        void appendingAMessageTouchesTheSession() {
            String kbId = kb("alice");
            store.createSession("s1", "t", kbId, "alice");
            sql("UPDATE sessions SET updated_at = '2020-01-01T00:00:00Z' WHERE id = 's1'");

            store.appendMessage("s1", "user", "问题", null, null, null, null);

            assertThat(store.getSession("s1").updatedAt()).isNotEqualTo("2020-01-01T00:00:00Z");
        }

        @Test
        void touchLeavesCreatedAtAlone() {
            String kbId = kb("alice");
            String createdAt = store.createSession("s1", "t", kbId, "alice").createdAt();
            sql("UPDATE sessions SET created_at = '2020-01-01T00:00:00Z' WHERE id = 's1'");
            store.touchSession("s1");
            assertThat(store.getSession("s1").createdAt()).isEqualTo("2020-01-01T00:00:00Z");
            assertThat(createdAt).isNotBlank();
        }
    }

    @Nested
    class Messages {

        private String kbId;

        @BeforeEach
        void session() {
            kbId = kb("alice");
            store.createSession("s1", "t", kbId, "alice");
        }

        @Test
        void indexesStartAtZeroAndIncrement() {
            assertThat(store.appendMessage("s1", "user", "问题", null, null, null, null)).isZero();
            assertThat(store.appendMessage("s1", "assistant", "答案", null, 0.8, "rag", null))
                    .isEqualTo(1);
        }

        @Test
        void indexesAreScopedToTheSession() {
            store.createSession("s2", "t", kbId, "alice");
            store.appendMessage("s1", "user", "a", null, null, null, null);
            assertThat(store.appendMessage("s2", "user", "b", null, null, null, null)).isZero();
        }

        @Test
        void indexesDoNotReuseAGap() {
            // MAX + 1, not COUNT. With COUNT, removing a message would make the next
            // one reuse a taken index, and feedback keys on (session_id,
            // message_index) — the new message would inherit the old one's rating.
            store.appendMessage("s1", "user", "a", null, null, null, null);
            store.appendMessage("s1", "user", "b", null, null, null, null);
            store.appendMessage("s1", "user", "c", null, null, null, null);
            sql("DELETE FROM messages WHERE session_id = 's1' AND message_index = 1");

            assertThat(store.appendMessage("s1", "user", "d", null, null, null, null)).isEqualTo(3);
        }

        @Test
        void sourcesRoundTripAsJson() {
            List<Map<String, Object>> sources = List.of(Map.of(
                    "text", "引用内容", "score", 0.87, "source", "a.txt", "document_id", "d1"));
            store.appendMessage("s1", "assistant", "答案", sources, 0.9, "hybrid", null);

            MetadataStore.MessageRow row = store.listMessages("s1", 0).get(0);
            assertThat(row.sources()).hasSize(1);
            assertThat(row.sources().get(0)).containsEntry("source", "a.txt");
            assertThat(row.confidence()).isEqualTo(0.9);
            assertThat(row.route()).isEqualTo("hybrid");
        }

        @Test
        void anEmptySourceListIsStoredAsNull() {
            store.appendMessage("s1", "assistant", "答案", List.of(), null, null, null);
            assertThat(store.listMessages("s1", 0).get(0).sources()).isNull();
        }

        @Test
        void aNullConfidenceStaysNull() {
            // The frontend hides the confidence bar for a user message; 0.0 would
            // render as a real "0%" reading.
            store.appendMessage("s1", "user", "问题", null, null, null, null);
            assertThat(store.listMessages("s1", 0).get(0).confidence()).isNull();
        }

        @Test
        void listedOldestFirst() {
            store.appendMessage("s1", "user", "一", null, null, null, null);
            store.appendMessage("s1", "assistant", "二", null, null, null, null);
            assertThat(store.listMessages("s1", 0)).extracting(MetadataStore.MessageRow::content)
                    .containsExactly("一", "二");
        }

        @Test
        void aLimitKeepsTheNewestButStillReturnsThemOldestFirst() {
            // This is what feeds the analyzer's history window, which reads the
            // messages in order.
            for (int i = 0; i < 5; i++) {
                store.appendMessage("s1", "user", "m" + i, null, null, null, null);
            }
            assertThat(store.listMessages("s1", 2)).extracting(MetadataStore.MessageRow::content)
                    .containsExactly("m3", "m4");
        }

        @Test
        void aLimitOfZeroMeansEverything() {
            store.appendMessage("s1", "user", "一", null, null, null, null);
            store.appendMessage("s1", "user", "二", null, null, null, null);
            assertThat(store.listMessages("s1", 0)).hasSize(2);
        }

        @Test
        void countsComeBackInOneQuery() {
            store.createSession("s2", "t", kbId, "alice");
            store.appendMessage("s1", "user", "a", null, null, null, null);
            store.appendMessage("s1", "user", "b", null, null, null, null);
            store.appendMessage("s2", "user", "c", null, null, null, null);

            assertThat(store.sessionMessageCounts()).containsEntry("s1", 2).containsEntry("s2", 1);
            assertThat(store.sessionMessageCount("s1")).isEqualTo(2);
        }

        @Test
        void aMessageInAMissingSessionIsRejected() {
            assertThatThrownBy(() -> store.appendMessage("no-such-session", "user", "x",
                    null, null, null, null)).isInstanceOf(MetadataStore.StoreException.class);
        }
    }

    @Nested
    class Feedback {

        @BeforeEach
        void session() {
            String kbId = kb("alice");
            store.createSession("s1", "t", kbId, "alice");
            store.appendMessage("s1", "assistant", "答案", null, 0.9, "rag", null);
        }

        @Test
        void aRatingIsCounted() {
            store.upsertFeedback("s1", 0, "alice", "up", null);
            MetadataStore.FeedbackStats stats = store.feedbackStats("alice");
            assertThat(stats.total()).isEqualTo(1);
            assertThat(stats.positive()).isEqualTo(1);
            assertThat(stats.satisfactionRate()).isEqualTo(1.0);
        }

        @Test
        void resubmittingOverwritesRatherThanAddingARow() {
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s1", 0, "alice", "down", null);

            MetadataStore.FeedbackStats stats = store.feedbackStats("alice");
            assertThat(stats.total()).isEqualTo(1);
            assertThat(stats.negative()).isEqualTo(1);
            assertThat(stats.positive()).isZero();
        }

        @Test
        void addingACommentLaterKeepsOneRow() {
            // The UI submits the rating on click and the comment afterwards. Two
            // rows here would double-count the vote.
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s1", 0, "alice", "up", "很有帮助");
            assertThat(store.feedbackStats("alice").total()).isEqualTo(1);
        }

        @Test
        void differentMessagesAreCountedSeparately() {
            store.appendMessage("s1", "assistant", "第二个答案", null, 0.9, "rag", null);
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s1", 1, "alice", "down", null);

            MetadataStore.FeedbackStats stats = store.feedbackStats("alice");
            assertThat(stats.total()).isEqualTo(2);
            assertThat(stats.satisfactionRate()).isEqualTo(0.5);
        }

        @Test
        void statsAreScopedToTheOwner() {
            store.createSession("s2", "t", kb("bob"), "bob");
            store.appendMessage("s2", "assistant", "答案", null, null, null, null);
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s2", 0, "bob", "down", null);

            assertThat(store.feedbackStats("alice").total()).isEqualTo(1);
            assertThat(store.feedbackStats("alice").positive()).isEqualTo(1);
        }

        @Test
        void aNullOwnerMeansSiteWide() {
            store.createSession("s2", "t", kb("bob"), "bob");
            store.appendMessage("s2", "assistant", "答案", null, null, null, null);
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s2", 0, "bob", "down", null);

            assertThat(store.feedbackStats(null).total()).isEqualTo(2);
        }

        @Test
        void noFeedbackIsZeroNotADivisionByZero() {
            MetadataStore.FeedbackStats stats = store.feedbackStats("alice");
            assertThat(stats.total()).isZero();
            assertThat(stats.satisfactionRate()).isZero();
        }

        @Test
        void theSatisfactionRateIsARatioNotAPercentage() {
            // Both the Python backend and the frontend expect 0-1 here.
            store.appendMessage("s1", "assistant", "b", null, null, null, null);
            store.appendMessage("s1", "assistant", "c", null, null, null, null);
            store.appendMessage("s1", "assistant", "d", null, null, null, null);
            store.upsertFeedback("s1", 0, "alice", "up", null);
            store.upsertFeedback("s1", 1, "alice", "up", null);
            store.upsertFeedback("s1", 2, "alice", "up", null);
            store.upsertFeedback("s1", 3, "alice", "down", null);

            assertThat(store.feedbackStats("alice").satisfactionRate()).isEqualTo(0.75);
        }

        @Test
        void anUnknownRatingIsCountedAsNeither() {
            store.upsertFeedback("s1", 0, "alice", "sideways", null);
            MetadataStore.FeedbackStats stats = store.feedbackStats("alice");
            assertThat(stats.total()).isZero();
        }
    }
}
