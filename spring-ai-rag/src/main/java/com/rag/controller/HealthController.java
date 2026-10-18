package com.rag.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness endpoints, matching the Python backend's {@code /} and {@code /health}.
 *
 * <p>{@link com.rag.config.SecurityConfig} already permits both paths anonymously,
 * but nothing served them, so the container health check hit a 404 and the
 * service never became healthy. Deliberately not Actuator: adding a management
 * endpoint for two literal strings would pull in a dependency and expose a whole
 * endpoint tree that needs securing.
 */
@RestController
public class HealthController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("message", "Spring AI RAG Backend is running");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }
}
