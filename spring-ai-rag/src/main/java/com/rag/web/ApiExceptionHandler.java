package com.rag.web;

import com.rag.store.MetadataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Uniform error bodies. Both keys are populated: {@code detail} matches FastAPI
 * (so the frontend can read one shape from either backend) and {@code error} is
 * kept for the existing frontend code path.
 *
 * <p>Deliberately narrow: anything not listed here falls through to Boot's
 * default error handling rather than being flattened into a generic 500 (which
 * would also swallow framework 404s and security denials).
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(body("File too large"));
    }

    /**
     * An {@link IllegalArgumentException} reaching this point is <em>not</em> a bad
     * request. Our own input validation throws {@link ApiException} with an explicit
     * status; what is left is library code failing — a stalled Ollama connection, a
     * Milvus schema mismatch — and reporting that as a 400 blamed the client for a
     * server problem, told them nothing useful ({@code "Self-suppression not
     * permitted"} was one real example), and logged nothing at all, so there was no
     * way to find out what had actually broken.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.error("Unhandled IllegalArgumentException", e);
        return ResponseEntity.internalServerError().body(body("Internal server error"));
    }

    @ExceptionHandler(MetadataStore.StoreException.class)
    public ResponseEntity<Map<String, Object>> handleStoreFailure(MetadataStore.StoreException e) {
        log.error("Metadata store failure", e);
        return ResponseEntity.internalServerError().body(body("Metadata store unavailable"));
    }

    private static Map<String, Object> body(String message) {
        String text = message == null ? "Request failed" : message;
        return Map.of("detail", text, "error", text);
    }
}
