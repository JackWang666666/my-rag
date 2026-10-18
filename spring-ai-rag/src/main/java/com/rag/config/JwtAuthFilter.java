package com.rag.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Populates the security context from a {@code Bearer} token.
 *
 * <p>The token is parsed once per request. The previous version called
 * {@code isValid} then {@code getUsername} then {@code getRole}, verifying the
 * signature three times, and would have thrown an NPE on a token whose
 * {@code role} claim was absent.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String DEFAULT_ROLE = "user";

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * {@code OncePerRequestFilter} skips {@code ASYNC} dispatches by default, which
     * fits the usual request/response cycle but breaks SSE: the servlet container
     * re-dispatches the request through the whole filter chain when the
     * {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
     * completes, and {@code AuthorizationFilter} runs on that dispatch too. Without
     * this override, the authentication this filter set is gone by then (nothing
     * persists it — the security context is {@code STATELESS}), so the trailing
     * dispatch gets rejected as anonymous even though the stream itself already sent
     * data, which is what surfaced as "Unable to handle the Spring Security
     * Exception because the response is already committed".
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            Claims claims = jwtUtil.parseToken(authHeader.substring(7).trim());
            String username = claims == null ? null : claims.getSubject();

            // An unauthenticated request is left anonymous here on purpose; the
            // security config decides which paths that is acceptable for.
            if (username != null && !username.isBlank()) {
                Object roleClaim = claims.get("role");
                String role = roleClaim == null || roleClaim.toString().isBlank()
                        ? DEFAULT_ROLE : roleClaim.toString();

                var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
                var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                auth.setDetails(Map.of("username", username, "role", role));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        chain.doFilter(request, response);
    }
}
