package com.rag.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD over the shared SQLite metadata store.
 *
 * <p>Java mirror of {@code langchain-rag/app/core/store.py}. Both backends read
 * and write the same rows, so the SQL here must stay semantically identical —
 * notably the chunk search (infix {@code LIKE ... ESCAPE}, which Milvus cannot
 * do) and the session owner filter.
 */
@Component
@Slf4j
public class MetadataStore {

    private final Db db;
    private final Gson gson = new Gson();

    public MetadataStore(Db db) {
        this.db = db;
    }

    /**
     * 32-char lowercase hex id.
     *
     * <p>Shared with the Python backend: the Milvus collection name is derived
     * directly from it ({@code kb_<32hex>}), so it must not contain dashes.
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // --- Row types -------------------------------------------------------

    public record UserRow(String username, String hashedPassword, String role, String createdAt) {}

    public record KbRow(String id, String name, String description, String kbType, String owner,
                        boolean isGlobal, int embeddingDim, String createdAt) {}

    public record DocRow(String id, String kbId, String filename, String fileType, long fileSize,
                         int chunkCount, String status, String stage, String error,
                         String createdAt) {}

    /** A chunk as written to both Milvus and the browsable copy table. */
    public record ChunkInput(String id, int chunkIndex, String source, String text,
                             Map<String, Object> metadata) {}

    public record ChunkRow(String id, String text, Map<String, Object> metadata, String documentId,
                           String source, int chunkIndex) {}

    public record ChunkPage(List<ChunkRow> chunks, int total) {}

    public record SessionRow(String id, String title, String kbId, String owner,
                             String createdAt, String updatedAt) {}

    /**
     * {@code sources} is the citation list as stored: a JSON array of objects with
     * keys {@code text}, {@code score}, {@code source}, {@code document_id} and
     * {@code rerank_score}. Null when the message has none.
     */
    public record MessageRow(int messageIndex, String role, String content,
                             List<Map<String, Object>> sources, Double confidence, String route,
                             String createdAt) {}

    public record FeedbackStats(int total, int positive, int negative, double satisfactionRate) {}

    // --- Users -----------------------------------------------------------

    public UserRow getUser(String username) {
        return queryOne("SELECT * FROM users WHERE username = ?", MetadataStore::mapUser, username);
    }

    public UserRow createUser(String username, String hashedPassword, String role) {
        String now = Db.nowIso();
        update("INSERT INTO users (username, hashed_password, role, created_at) VALUES (?, ?, ?, ?)",
                username, hashedPassword, role, now);
        return new UserRow(username, hashedPassword, role, now);
    }

    public int userCount() {
        return count("SELECT COUNT(*) FROM users");
    }

    // --- Knowledge bases -------------------------------------------------

    public KbRow createKb(String kbId, String name, String description, String kbType, String owner,
                          boolean isGlobal, int embeddingDim) {
        update("INSERT INTO knowledge_bases"
                        + " (id, name, description, kb_type, owner, is_global, embedding_dim, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                kbId, name, description, kbType, owner, isGlobal ? 1 : 0, embeddingDim, Db.nowIso());
        return getKb(kbId);
    }

    public KbRow getKb(String kbId) {
        return queryOne("SELECT * FROM knowledge_bases WHERE id = ?", MetadataStore::mapKb, kbId);
    }

    public List<KbRow> listKbs() {
        return queryList("SELECT * FROM knowledge_bases ORDER BY created_at DESC", MetadataStore::mapKb);
    }

    /** Global KBs plus the user's own. Admins see everything. */
    public List<KbRow> listVisibleKbs(String username, String role) {
        if ("admin".equals(role)) {
            return listKbs();
        }
        return queryList("SELECT * FROM knowledge_bases WHERE is_global = 1 OR owner = ?"
                + " ORDER BY created_at DESC", MetadataStore::mapKb, username);
    }

    /**
     * The global KBs, newest first, excluding {@code exclude} (pass the current KB).
     *
     * <p>Whole rows rather than ids, because the router has to decide whether a
     * question is covered by <em>any</em> searchable KB and a bare id tells it
     * nothing. The query already selected the names; {@link #listGlobalKbIds} used
     * to load them and drop them one line later, which is precisely why asking a
     * private KB about a global KB's subject was routed {@code direct} and answered
     * from the model's own knowledge with the global KB never searched.
     */
    public List<KbRow> listGlobalKbs(String exclude) {
        List<KbRow> kbs = new ArrayList<>();
        for (KbRow kb : queryList("SELECT * FROM knowledge_bases WHERE is_global = 1"
                + " ORDER BY created_at DESC", MetadataStore::mapKb)) {
            if (!kb.id().equals(exclude)) {
                kbs.add(kb);
            }
        }
        return kbs;
    }

    /**
     * The caller's own private (non-global) knowledge bases, newest first.
     *
     * <p>Deliberately not {@link #listVisibleKbs}: that returns every KB for an
     * admin, but "all my knowledge bases" means the caller's own private ones
     * only — global-KB merging is a separate, orthogonal step in the chat
     * pipeline.
     */
    public List<KbRow> listOwnedKbs(String username) {
        return queryList("SELECT * FROM knowledge_bases WHERE owner = ? AND is_global = 0"
                + " ORDER BY created_at DESC", MetadataStore::mapKb, username);
    }

    public List<String> listGlobalKbIds(String exclude) {
        List<String> ids = new ArrayList<>();
        for (KbRow kb : listGlobalKbs(exclude)) {
            ids.add(kb.id());
        }
        return ids;
    }

    /** Documents and chunks cascade via the foreign keys. */
    public boolean deleteKb(String kbId) {
        return update("DELETE FROM knowledge_bases WHERE id = ?", kbId) > 0;
    }

    public int kbDocumentCount(String kbId) {
        return count("SELECT COUNT(*) FROM documents WHERE kb_id = ?", kbId);
    }

    /** document_count for every KB in one query (avoids N+1 on the list view). */
    public Map<String, Integer> kbDocumentCounts() {
        Map<String, Integer> counts = new HashMap<>();
        try (Connection conn = db.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT kb_id, COUNT(*) AS c FROM documents GROUP BY kb_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("kb_id"), rs.getInt("c"));
            }
        } catch (SQLException e) {
            throw new StoreException("kbDocumentCounts failed", e);
        }
        return counts;
    }

    // --- Documents -------------------------------------------------------

    public DocRow createDocument(String docId, String kbId, String filename, String fileType,
                                 long fileSize, String status) {
        update("INSERT INTO documents"
                        + " (id, kb_id, filename, file_type, file_size, chunk_count, status, error, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?, NULL, ?)",
                docId, kbId, filename, fileType, fileSize, status, Db.nowIso());
        return getDocument(docId);
    }

    public DocRow getDocument(String docId) {
        return queryOne("SELECT * FROM documents WHERE id = ?", MetadataStore::mapDoc, docId);
    }

    public List<DocRow> listDocuments(String kbId) {
        return queryList("SELECT * FROM documents WHERE kb_id = ? ORDER BY created_at DESC",
                MetadataStore::mapDoc, kbId);
    }

    public void setDocumentStage(String docId, String stage) {
        update("UPDATE documents SET stage = ? WHERE id = ?", stage, docId);
    }

    public void markDocumentCompleted(String docId, int chunkCount) {
        update("UPDATE documents SET status = 'completed', chunk_count = ?, error = NULL WHERE id = ?",
                chunkCount, docId);
    }

    public void markDocumentFailed(String docId, String error) {
        String message = error == null ? "unknown error" : error;
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        update("UPDATE documents SET status = 'failed', error = ? WHERE id = ?", message, docId);
    }

    public boolean deleteDocument(String docId) {
        update("DELETE FROM chunks WHERE document_id = ?", docId);
        return update("DELETE FROM documents WHERE id = ?", docId) > 0;
    }

    // --- Chunks ----------------------------------------------------------

    /** Insert the browsable copies of chunks written to Milvus. */
    public void insertChunks(String kbId, String documentId, List<ChunkInput> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        String now = Db.nowIso();
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO chunks"
                    + " (id, kb_id, document_id, chunk_index, source, text, metadata, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (ChunkInput c : chunks) {
                    ps.setString(1, c.id());
                    ps.setString(2, kbId);
                    ps.setString(3, documentId);
                    ps.setInt(4, c.chunkIndex());
                    ps.setString(5, c.source() == null ? "" : c.source());
                    ps.setString(6, c.text() == null ? "" : c.text());
                    ps.setString(7, gson.toJson(c.metadata() == null ? Map.of() : c.metadata()));
                    ps.setString(8, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new StoreException("insertChunks failed", e);
        }
    }

    /**
     * Paginated chunk listing with real infix search.
     *
     * <p>Milvus 2.4's {@code like} only supports prefix matching, so {@code %keyword%}
     * search has to run here. Parameter binding also removes the expression-injection
     * surface that string-concatenated Milvus filters had.
     */
    public ChunkPage listChunks(String kbId, int page, int pageSize, String search) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 200));
        int offset = (safePage - 1) * safeSize;

        StringBuilder where = new StringBuilder("kb_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(kbId);
        if (search != null && !search.isEmpty()) {
            // ESCAPE so a literal % or _ in the query is not a wildcard.
            String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append(" AND (text LIKE ? ESCAPE '\\' OR source LIKE ? ESCAPE '\\')");
            params.add("%" + escaped + "%");
            params.add("%" + escaped + "%");
        }

        int total = count("SELECT COUNT(*) FROM chunks WHERE " + where, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add(offset);
        List<ChunkRow> rows = queryList("SELECT * FROM chunks WHERE " + where
                        + " ORDER BY document_id, chunk_index LIMIT ? OFFSET ?",
                this::mapChunk, pageParams.toArray());

        return new ChunkPage(rows, total);
    }

    public int countChunks(String kbId) {
        return count("SELECT COUNT(*) FROM chunks WHERE kb_id = ?", kbId);
    }

    // --- Sessions --------------------------------------------------------

    public SessionRow createSession(String sessionId, String title, String kbId, String owner) {
        String now = Db.nowIso();
        update("INSERT INTO sessions (id, title, kb_id, owner, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", sessionId, title, kbId, owner, now, now);
        return new SessionRow(sessionId, title, kbId, owner, now, now);
    }

    public SessionRow getSession(String sessionId) {
        return queryOne("SELECT * FROM sessions WHERE id = ?", MetadataStore::mapSession, sessionId);
    }

    /**
     * Sessions visible to {@code owner}.
     *
     * <p>Non-admins only ever see their own sessions — without the owner filter any
     * logged-in user could enumerate (and then read) every conversation.
     */
    public List<SessionRow> listSessions(String owner, String role, String kbId) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!"admin".equals(role)) {
            where.add("owner = ?");
            params.add(owner);
        }
        if (kbId != null && !kbId.isEmpty()) {
            where.add("kb_id = ?");
            params.add(kbId);
        }
        String sql = "SELECT * FROM sessions";
        if (!where.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", where);
        }
        sql += " ORDER BY updated_at DESC";
        return queryList(sql, MetadataStore::mapSession, params.toArray());
    }

    public void touchSession(String sessionId) {
        update("UPDATE sessions SET updated_at = ? WHERE id = ?", Db.nowIso(), sessionId);
    }

    public void renameSession(String sessionId, String title) {
        update("UPDATE sessions SET title = ? WHERE id = ?", title, sessionId);
    }

    public boolean deleteSession(String sessionId) {
        update("DELETE FROM feedback WHERE session_id = ?", sessionId);
        return update("DELETE FROM sessions WHERE id = ?", sessionId) > 0;
    }

    public Map<String, Integer> sessionMessageCounts() {
        Map<String, Integer> counts = new HashMap<>();
        try (Connection conn = db.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT session_id, COUNT(*) AS c FROM messages GROUP BY session_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("session_id"), rs.getInt("c"));
            }
        } catch (SQLException e) {
            throw new StoreException("sessionMessageCounts failed", e);
        }
        return counts;
    }

    public int sessionMessageCount(String sessionId) {
        return count("SELECT COUNT(*) FROM messages WHERE session_id = ?", sessionId);
    }

    // --- Messages --------------------------------------------------------

    /** Append a message and return its index within the session. */
    public int appendMessage(String sessionId, String role, String content,
                             List<Map<String, Object>> sources, Double confidence, String route) {
        int nextIndex = count("SELECT COALESCE(MAX(message_index) + 1, 0) FROM messages"
                + " WHERE session_id = ?", sessionId);
        update("INSERT INTO messages"
                        + " (id, session_id, message_index, role, content, sources, confidence, route, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                newId(), sessionId, nextIndex, role, content,
                sources == null || sources.isEmpty() ? null : gson.toJson(sources),
                confidence, route, Db.nowIso());
        touchSession(sessionId);
        return nextIndex;
    }

    /** Messages in a session, oldest first. {@code limit <= 0} means all of them. */
    public List<MessageRow> listMessages(String sessionId, int limit) {
        if (limit > 0) {
            List<MessageRow> newestFirst = queryList("SELECT * FROM messages WHERE session_id = ?"
                            + " ORDER BY message_index DESC LIMIT ?",
                    this::mapMessage, sessionId, limit);
            List<MessageRow> oldestFirst = new ArrayList<>(newestFirst);
            java.util.Collections.reverse(oldestFirst);
            return oldestFirst;
        }
        return queryList("SELECT * FROM messages WHERE session_id = ? ORDER BY message_index",
                this::mapMessage, sessionId);
    }

    // --- Feedback --------------------------------------------------------

    /** Record feedback; re-submitting for the same message overwrites it. */
    public void upsertFeedback(String sessionId, int messageIndex, String owner, String rating,
                               String comment) {
        update("INSERT INTO feedback"
                        + " (id, session_id, message_index, owner, rating, comment, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(session_id, message_index) DO UPDATE SET"
                        + "   rating = excluded.rating,"
                        + "   comment = excluded.comment,"
                        + "   owner = excluded.owner,"
                        + "   created_at = excluded.created_at",
                newId(), sessionId, messageIndex, owner, rating, comment, Db.nowIso());
    }

    /**
     * Feedback totals. {@code owner == null} means site-wide (admin view).
     *
     * <p>{@code satisfactionRate} is a 0-1 ratio, not a percentage — the Python
     * backend and the frontend both expect a ratio.
     */
    public FeedbackStats feedbackStats(String owner) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String sql = owner != null
                ? "SELECT rating, COUNT(*) AS c FROM feedback WHERE owner = ? GROUP BY rating"
                : "SELECT rating, COUNT(*) AS c FROM feedback GROUP BY rating";
        try (Connection conn = db.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (owner != null) {
                ps.setString(1, owner);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("rating"), rs.getInt("c"));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("feedbackStats failed", e);
        }
        int positive = counts.getOrDefault("up", 0);
        int negative = counts.getOrDefault("down", 0);
        int total = positive + negative;
        double rate = total == 0 ? 0.0 : Math.round((double) positive / total * 10000.0) / 10000.0;
        return new FeedbackStats(total, positive, negative, rate);
    }

    // --- Row mappers -----------------------------------------------------

    private static UserRow mapUser(ResultSet rs) throws SQLException {
        return new UserRow(rs.getString("username"), rs.getString("hashed_password"),
                rs.getString("role"), rs.getString("created_at"));
    }

    private static KbRow mapKb(ResultSet rs) throws SQLException {
        return new KbRow(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("kb_type"), rs.getString("owner"), rs.getInt("is_global") != 0,
                rs.getInt("embedding_dim"), rs.getString("created_at"));
    }

    private static DocRow mapDoc(ResultSet rs) throws SQLException {
        return new DocRow(rs.getString("id"), rs.getString("kb_id"), rs.getString("filename"),
                rs.getString("file_type"), rs.getLong("file_size"), rs.getInt("chunk_count"),
                rs.getString("status"), rs.getString("stage"), rs.getString("error"),
                rs.getString("created_at"));
    }

    private static SessionRow mapSession(ResultSet rs) throws SQLException {
        return new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("kb_id"),
                rs.getString("owner"), rs.getString("created_at"), rs.getString("updated_at"));
    }

    private ChunkRow mapChunk(ResultSet rs) throws SQLException {
        return new ChunkRow(rs.getString("id"), rs.getString("text"),
                parseMetadata(rs.getString("metadata")), rs.getString("document_id"),
                rs.getString("source"), rs.getInt("chunk_index"));
    }

    private MessageRow mapMessage(ResultSet rs) throws SQLException {
        double confidence = rs.getDouble("confidence");
        Double boxedConfidence = rs.wasNull() ? null : confidence;
        return new MessageRow(rs.getInt("message_index"), rs.getString("role"),
                rs.getString("content"), parseSources(rs.getString("sources")),
                boxedConfidence, rs.getString("route"), rs.getString("created_at"));
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = gson.fromJson(json,
                    new TypeToken<Map<String, Object>>() {}.getType());
            return parsed != null ? parsed : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private List<Map<String, Object>> parseSources(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return gson.fromJson(json,
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
        } catch (Exception e) {
            return null;
        }
    }

    // --- Tiny JDBC helpers -----------------------------------------------

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = queryList(sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        List<T> out = new ArrayList<>();
        try (Connection conn = db.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Query failed: " + sql, e);
        }
        return out;
    }

    private int update(String sql, Object... params) {
        try (Connection conn = db.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Update failed: " + sql, e);
        }
    }

    private int count(String sql, Object... params) {
        try (Connection conn = db.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StoreException("Count failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Unchecked wrapper: metadata-store failures are 500s, not per-call branches. */
    public static class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
