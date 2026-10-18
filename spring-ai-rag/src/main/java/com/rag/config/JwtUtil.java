package com.rag.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * HS256 token issue/verify.
 *
 * <p>The secret comes from {@code JWT_SECRET} (never hardcoded). Set the same
 * value here and in the Python backend and tokens are interchangeable: both sign
 * {@code {sub, role, exp}} with HS256 over the raw UTF-8 bytes of the secret.
 */
@Component
@Slf4j
public class JwtUtil {

    /** HS256 needs a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final RagProperties props;
    private SecretKey key;
    private long expirationMs;

    public JwtUtil(RagProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        byte[] secret = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            // Padding or hashing the secret would silently break token
            // interchange with the Python backend, so refuse to start instead.
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES
                    + " characters (HS256 requires a 256-bit key); got " + secret.length);
        }
        key = Keys.hmacShaKeyFor(secret);
        expirationMs = props.getAccessTokenExpireHours() * 60L * 60L * 1000L;
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /** Parsed claims, or null when the token is missing, malformed or expired. */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token rejected: {}", e.getMessage());
            return null;
        }
    }

    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    public String getRole(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        Object role = claims.get("role");
        return role != null ? role.toString() : "user";
    }

    public boolean isValid(String token) {
        return parseToken(token) != null;
    }
}
