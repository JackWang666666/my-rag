package com.rag.store;

import com.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirror of {@code langchain-rag/tests/test_db.py}.
 *
 * <p>The timestamp format matters more than it looks: the Python backend parses
 * these strings and writes its own in the same shape, and both back ends' listing
 * endpoints sort on the raw text. A value in a different shape is not a cosmetic
 * difference — it sorts wrongly and fails {@link Instant#parse}.
 */
class DbTest {

    private static Db open(Path dir) {
        RagProperties props = new RagProperties();
        props.setDbPath(dir.resolve("rag.db").toString());
        Db db = new Db(props);
        db.init();
        return db;
    }

    @Test
    void allSharedTablesExist(@TempDir Path dir) throws Exception {
        Db db = open(dir);
        Set<String> names = new HashSet<>();
        try (Connection conn = db.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        assertThat(names).contains(
                "users", "knowledge_bases", "documents", "chunks", "sessions", "messages",
                "feedback");
    }

    @Test
    void initIsIdempotent(@TempDir Path dir) {
        // Every statement is CREATE ... IF NOT EXISTS, so starting against an
        // existing database — including one the Python backend created — must work.
        Db db = open(dir);
        assertThatCode(db::init).doesNotThrowAnyException();
        assertThatCode(db::init).doesNotThrowAnyException();
    }

    @Test
    void theParentDirectoryIsCreated(@TempDir Path dir) {
        RagProperties props = new RagProperties();
        props.setDbPath(dir.resolve("nested/deeper/rag.db").toString());
        Db db = new Db(props);
        db.init();
        assertThat(Files.isRegularFile(dir.resolve("nested/deeper/rag.db"))).isTrue();
    }

    @Test
    void thePathIsAbsoluteAndNormalised(@TempDir Path dir) {
        RagProperties props = new RagProperties();
        props.setDbPath(dir.resolve("a/../rag.db").toString());
        Db db = new Db(props);
        db.init();
        assertThat(db.path().isAbsolute()).isTrue();
        assertThat(db.path()).isEqualTo(dir.resolve("rag.db"));
    }

    @Test
    void walIsEnabled(@TempDir Path dir) throws Exception {
        // Without WAL the two backends block each other: a writer would lock out
        // every reader on the shared file.
        Db db = open(dir);
        try (Connection conn = db.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void foreignKeysAreEnforced(@TempDir Path dir) throws Exception {
        Db db = open(dir);
        try (Connection conn = db.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void feedbackIsUniquePerMessage(@TempDir Path dir) throws Exception {
        Db db = open(dir);
        boolean hasUnique = false;
        try (Connection conn = db.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA index_list(feedback)")) {
            while (rs.next()) {
                hasUnique |= rs.getInt("unique") == 1;
            }
        }
        assertThat(hasUnique).isTrue();
    }

    @Test
    void chineseTextRoundTrips(@TempDir Path dir) throws Exception {
        Db db = open(dir);
        try (Connection conn = db.open(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO users (username, hashed_password, role, created_at)"
                    + " VALUES ('张三', 'hash', 'user', '" + Db.nowIso() + "')");
        }
        try (Connection conn = db.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("张三");
        }
    }

    @Test
    void nowIsoIsUtcWithATrailingZ() {
        String value = Db.nowIso();
        assertThat(value).endsWith("Z");
        // No sub-second part and no numeric offset: the Python backend's
        // `%Y-%m-%dT%H:%M:%SZ` strptime accepts exactly this.
        assertThat(value).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void nowIsoIsActuallyUtc() {
        Instant before = Instant.now().minusSeconds(5);
        Instant parsed = Instant.parse(Db.nowIso());
        assertThat(parsed).isBetween(before, Instant.now().plusSeconds(5));
    }

    @Test
    void toIsoFormatsAKnownInstant() {
        assertThat(Db.toIso(Instant.parse("2026-01-02T03:04:05Z"))).isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void toIsoDropsSubSecondPrecision() {
        assertThat(Db.toIso(Instant.parse("2026-01-02T03:04:05.987654Z")))
                .isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void toIsoConvertsToUtc() {
        // The formatter must not pick up the JVM's default zone — a machine on
        // Asia/Shanghai would otherwise write +08:00 wall-clock values that the
        // Python side reads back as UTC.
        Instant instant = OffsetDateTime.parse("2026-01-02T11:04:05+08:00").toInstant();
        assertThat(Db.toIso(instant)).isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void whatWeWriteIsWhatInstantParseAccepts() {
        assertThatCode(() -> Instant.parse(Db.nowIso())).doesNotThrowAnyException();
    }

    @Test
    void roundTripsThroughInstant() {
        String written = Db.nowIso();
        assertThat(Db.toIso(Instant.parse(written))).isEqualTo(written);
    }

    @Test
    void sqlitesOwnTimestampShapeIsNotWhatWeWrite() {
        // Documents the constraint rather than the code: `CURRENT_TIMESTAMP` gives
        // "2026-01-02 03:04:05", which Instant.parse rejects. Every insert has to
        // pass nowIso() explicitly, which is why no column defaults to it.
        assertThatThrownBy(() -> Instant.parse("2026-01-02 03:04:05"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
