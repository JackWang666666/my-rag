package com.rag.controller;

import com.rag.config.JwtUtil;
import com.rag.service.UserService;
import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Login and registration (docs/CONTRACT.md section 5).
 *
 * <p>Both endpoints return {@code {token, username, role}}. Failures go through
 * {@link ApiException} so the error body matches every other endpoint's.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    @Data
    public static class Credentials {
        private String username;
        private String password;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Credentials req) {
        MetadataStore.UserRow user = userService.authenticate(req.getUsername(), req.getPassword());
        if (user == null) {
            throw new ApiException(401, "Invalid username or password");
        }
        return tokenResponse(user);
    }

    @PostMapping("/register")
    public Map<String, String> register(@RequestBody Credentials req) {
        // UserService rejects a bad username or password with ApiException.badRequest,
        // which the handler turns into a 400 carrying its message.
        return tokenResponse(userService.register(req.getUsername(), req.getPassword()));
    }

    private Map<String, String> tokenResponse(MetadataStore.UserRow user) {
        return Map.of(
                "token", jwtUtil.generateToken(user.username(), user.role()),
                "username", user.username(),
                "role", user.role());
    }
}
