package com.rag.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * The authenticated caller, as put into the security context by
 * {@link com.rag.config.JwtAuthFilter}.
 */
public record CurrentUser(String username, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    /** Resolve from the security context. Never null: unauthenticated is rejected earlier. */
    @SuppressWarnings("unchecked")
    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ApiException(401, "Not authenticated");
        }
        String role = "user";
        if (auth.getDetails() instanceof Map<?, ?> details) {
            Object value = ((Map<String, Object>) details).get("role");
            if (value != null) {
                role = value.toString();
            }
        }
        return new CurrentUser(auth.getName(), role);
    }
}
