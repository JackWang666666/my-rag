package com.rag.service;

import com.rag.config.RagProperties;
import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Accounts in the shared SQLite store.
 *
 * <p>Users used to live in a {@code ConcurrentHashMap} with a hardcoded
 * admin/admin123, so every account vanished on restart and the Python backend
 * could not see any of them. Both backends now read the same {@code users} table.
 *
 * <p>Hash interoperability: Spring's {@code BCryptPasswordEncoder} writes
 * {@code $2a$} hashes at cost 10 and the Python backend's bcrypt writes
 * {@code $2b$} at cost 12; both verify either prefix at any cost, so a user
 * registered on one backend can log in on the other.
 */
@Service
@Slf4j
public class UserService {

    private final MetadataStore store;
    private final PasswordEncoder passwordEncoder;
    private final RagProperties props;

    public UserService(MetadataStore store, PasswordEncoder passwordEncoder, RagProperties props) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    /** Create the bootstrap admin account if it does not exist yet. */
    @PostConstruct
    public void initAdmin() {
        String username = props.getAdminUsername();
        if (store.getUser(username) == null) {
            store.createUser(username, passwordEncoder.encode(props.getAdminPassword()), "admin");
            log.info("Created bootstrap admin user '{}'", username);
        }
    }

    /** The authenticated user, or null when the credentials do not match. */
    public MetadataStore.UserRow authenticate(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        MetadataStore.UserRow user = store.getUser(username);
        if (user == null) {
            return null;
        }
        try {
            if (!passwordEncoder.matches(password, user.hashedPassword())) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            // Malformed hash in the DB (e.g. written by a different tool).
            log.warn("Stored password hash for '{}' is not usable", username);
            return null;
        }
        return user;
    }

    /**
     * Minimum lengths, kept in step with the Python backend's
     * {@code app/api/auth.py}: both backends write to the same {@code users}
     * table, so a credential accepted by one must be accepted by the other.
     */
    public static final int MIN_USERNAME_LENGTH = 2;
    public static final int MAX_USERNAME_LENGTH = 50;
    public static final int MIN_PASSWORD_LENGTH = 6;

    /**
     * bcrypt only hashes the first 72 bytes, and implementations disagree on the
     * remainder: OpenBSD truncates, the jBCrypt code behind
     * {@code BCryptPasswordEncoder} folds them into the key schedule, and the
     * Python backend's bcrypt refuses outright. Rejecting the whole range keeps
     * every account usable from both backends. Counted in UTF-8 bytes, so a
     * Chinese password caps out at 24 characters.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    public MetadataStore.UserRow register(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.length() < MIN_USERNAME_LENGTH || name.length() > MAX_USERNAME_LENGTH) {
            throw ApiException.badRequest("Username must be between "
                    + MIN_USERNAME_LENGTH + " and " + MAX_USERNAME_LENGTH + " characters");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.badRequest(
                    "Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (store.getUser(name) != null) {
            throw ApiException.badRequest("Username already exists");
        }
        return store.createUser(name, passwordEncoder.encode(password), "user");
    }
}
