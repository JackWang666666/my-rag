package com.rag.store;

import com.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The shared SQLite metadata store.
 *
 * <p>Schema, pragmas and timestamp format are fixed by {@code docs/CONTRACT.md}
 * section 1 and must stay byte-identical to
 * {@code langchain-rag/app/core/db.py} — both backends open the same file.
 */
@Component
@Slf4j
public class Db {

    /** Identical to SCHEMA in langchain-rag/app/core/db.py. */
    public static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS users (
                username        TEXT PRIMARY KEY,
                hashed_password TEXT NOT NULL,
                role            TEXT NOT NULL DEFAULT 'user',
                created_at      TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS knowledge_bases (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                description   TEXT NOT NULL DEFAULT '',
                kb_type       TEXT NOT NULL DEFAULT 'document',
                owner         TEXT NOT NULL,
                is_global     INTEGER NOT NULL DEFAULT 0,
                embedding_dim INTEGER NOT NULL DEFAULT 1536,
                created_at    TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS documents (
                id          TEXT PRIMARY KEY,
                kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                filename    TEXT NOT NULL,
                file_type   TEXT NOT NULL,
                file_size   INTEGER NOT NULL DEFAULT 0,
                chunk_count INTEGER NOT NULL DEFAULT 0,
                status      TEXT NOT NULL DEFAULT 'processing',
                stage       TEXT,
                error       TEXT,
                created_at  TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_documents_kb ON documents(kb_id);

            CREATE TABLE IF NOT EXISTS chunks (
                id          TEXT PRIMARY KEY,
                kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                document_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                source      TEXT NOT NULL DEFAULT '',
                text        TEXT NOT NULL,
                metadata    TEXT NOT NULL DEFAULT '{}',
                created_at  TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_chunks_kb  ON chunks(kb_id);
            CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document_id);

            CREATE TABLE IF NOT EXISTS sessions (
                id         TEXT PRIMARY KEY,
                title      TEXT NOT NULL,
                kb_id      TEXT NOT NULL,
                owner      TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_sessions_owner ON sessions(owner);

            CREATE TABLE IF NOT EXISTS messages (
                id            TEXT PRIMARY KEY,
                session_id    TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                message_index INTEGER NOT NULL,
                role          TEXT NOT NULL,
                content       TEXT NOT NULL,
                sources       TEXT,
                confidence    REAL,
                route         TEXT,
                created_at    TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);

            CREATE TABLE IF NOT EXISTS feedback (
                id            TEXT PRIMARY KEY,
                session_id    TEXT NOT NULL,
                message_index INTEGER NOT NULL,
                owner         TEXT NOT NULL,
                rating        TEXT NOT NULL,
                comment       TEXT,
                created_at    TEXT NOT NULL,
                UNIQUE(session_id, message_index)
            );
            """;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final RagProperties props;
    private Path resolvedPath;

    public Db(RagProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        resolvedPath = Paths.get(props.getDbPath()).toAbsolutePath().normalize();
        try {
            Path parent = resolvedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory for " + resolvedPath, e);
        }

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // executeUpdate takes a single statement; the schema is a script.
            for (String stmt : SCHEMA.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.executeUpdate(trimmed);
                }
            }
            migrate(st);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialise metadata store at " + resolvedPath, e);
        }
        log.info("Metadata store: {}", resolvedPath);
    }

    public Path path() {
        return resolvedPath;
    }

    /**
     * Add columns introduced after a table's {@code CREATE TABLE IF NOT EXISTS}
     * first ran.
     *
     * <p>{@code IF NOT EXISTS} only creates a table once; it never adds columns
     * to one that already exists, so a column added here has to be backfilled
     * explicitly on an existing database file. Mirrors {@code _migrate} in
     * {@code langchain-rag/app/core/db.py}.
     */
    private void migrate(Statement st) throws SQLException {
        boolean hasStage = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(documents)")) {
            while (rs.next()) {
                if ("stage".equals(rs.getString("name"))) {
                    hasStage = true;
                    break;
                }
            }
        }
        if (!hasStage) {
            st.executeUpdate("ALTER TABLE documents ADD COLUMN stage TEXT");
        }
    }

    /**
     * A new connection with the contract's pragmas applied.
     *
     * <p>SQLite handles concurrent access between the two backends through WAL;
     * connections are short-lived and opened per operation rather than pooled, so
     * neither backend holds a write lock across an LLM call.
     */
    public Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + resolvedPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=30000");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    /** Current UTC timestamp in the contract's format. */
    public static String nowIso() {
        return ISO.format(Instant.now());
    }

    public static String toIso(Instant instant) {
        return ISO.format(instant);
    }
}
