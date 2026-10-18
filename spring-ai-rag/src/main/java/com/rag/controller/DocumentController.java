package com.rag.controller;

import com.rag.config.RagProperties;
import com.rag.model.dto.DocumentResponse;
import com.rag.service.IngestService;
import com.rag.service.PermissionService;
import com.rag.service.RetrievalService;
import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import com.rag.web.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Document upload and management (docs/CONTRACT.md section 5).
 *
 * <p>Uploads return immediately with {@code status="processing"}; the parse →
 * chunk → embed → store pipeline runs in {@link IngestService} on a background
 * executor and the frontend polls {@code GET /api/documents/{id}}. Previously all
 * of it happened inline, so a large PDF held the request open for minutes.
 */
@RestController
@Slf4j
public class DocumentController {

    /** Extensions accepted for a single-file upload; mirrors the Python backend. */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            ".pdf", ".docx", ".md", ".txt", ".csv", ".xlsx", ".xls",
            ".py", ".js", ".ts", ".java", ".go", ".rs", ".cpp", ".c",
            ".h", ".rb", ".php", ".log");

    private final MetadataStore store;
    private final PermissionService permissions;
    private final IngestService ingestService;
    private final RetrievalService retrievalService;
    private final RagProperties props;

    public DocumentController(MetadataStore store, PermissionService permissions,
                              IngestService ingestService, RetrievalService retrievalService,
                              RagProperties props) {
        this.store = store;
        this.permissions = permissions;
        this.ingestService = ingestService;
        this.retrievalService = retrievalService;
        this.props = props;
    }

    @PostMapping("/api/knowledge-bases/{kbId}/documents")
    public DocumentResponse upload(@PathVariable String kbId,
                                   @RequestParam("file") MultipartFile file) {
        permissions.requireKbWrite(kbId, CurrentUser.get());

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw ApiException.badRequest("No filename provided");
        }
        String fileExt = extensionOf(filename);
        if (!SUPPORTED_TYPES.contains(fileExt)) {
            throw ApiException.badRequest("Unsupported file type: " + fileExt
                    + ". Supported: " + new TreeSet<>(SUPPORTED_TYPES));
        }
        requireNonEmptyAndWithinLimit(file);

        String docId = MetadataStore.newId();
        Path uploadDir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
        Path filePath = uploadDir.resolve(docId + fileExt);
        long fileSize = save(file, filePath);

        MetadataStore.DocRow doc = store.createDocument(docId, kbId, filename, fileExt,
                fileSize, "processing");
        ingestService.ingestFile(kbId, docId, filePath, fileExt, filename);
        return toResponse(doc);
    }

    @PostMapping("/api/knowledge-bases/{kbId}/import-project")
    public DocumentResponse importProject(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "fileExtensions", required = false) String fileExtensions) {
        permissions.requireKbWrite(kbId, CurrentUser.get());

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw ApiException.badRequest("File must be a ZIP archive");
        }
        requireNonEmptyAndWithinLimit(file);

        Path tempDir;
        try {
            // Removed by IngestService.ingestProject once it finishes.
            tempDir = Files.createTempDirectory("rag-import-");
        } catch (IOException e) {
            throw new ApiException(500, "Failed to create temp directory: " + e.getMessage());
        }

        Path zipPath = tempDir.resolve("project.zip");
        long fileSize = save(file, zipPath);

        String docId = MetadataStore.newId();
        MetadataStore.DocRow doc = store.createDocument(docId, kbId, filename, ".zip",
                fileSize, "processing");
        ingestService.ingestProject(kbId, docId, zipPath, tempDir, fileExtensions);
        return toResponse(doc);
    }

    @GetMapping("/api/knowledge-bases/{kbId}/documents")
    public List<DocumentResponse> list(@PathVariable String kbId) {
        permissions.requireKbRead(kbId, CurrentUser.get());
        List<DocumentResponse> documents = new ArrayList<>();
        for (MetadataStore.DocRow doc : store.listDocuments(kbId)) {
            documents.add(toResponse(doc));
        }
        return documents;
    }

    /** One document. This is what the frontend polls for ingestion status. */
    @GetMapping("/api/documents/{docId}")
    public DocumentResponse get(@PathVariable String docId) {
        return toResponse(permissions.requireDocumentAccess(docId, CurrentUser.get(), false));
    }

    @DeleteMapping("/api/documents/{docId}")
    public Map<String, String> delete(@PathVariable String docId) {
        MetadataStore.DocRow doc = permissions.requireDocumentAccess(docId, CurrentUser.get(), true);

        try {
            retrievalService.deleteByDocument(doc.kbId(), docId);
        } catch (Exception e) {
            throw new ApiException(500, "Failed to delete vectors: " + e.getMessage());
        }

        Path uploaded = Paths.get(props.getUploadDir()).toAbsolutePath().normalize()
                .resolve(docId + (doc.fileType() == null ? "" : doc.fileType()));
        try {
            Files.deleteIfExists(uploaded);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", uploaded, e.getMessage());
        }

        store.deleteDocument(docId);
        return Map.of("message", "Document deleted successfully");
    }

    // --- Helpers ----------------------------------------------------------

    /**
     * The container already enforces {@code spring.servlet.multipart.max-file-size},
     * but check explicitly too: that limit is configured from the same
     * {@code MAX_UPLOAD_MB} and a mismatch would otherwise surface as a raw 500.
     */
    private void requireNonEmptyAndWithinLimit(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Uploaded file is empty");
        }
        if (file.getSize() > props.maxUploadBytes()) {
            throw ApiException.payloadTooLarge(
                    "File exceeds the " + props.getMaxUploadMb() + " MB limit");
        }
    }

    private static long save(MultipartFile file, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            // Streamed rather than getBytes(): a 50 MB getBytes() call allocates the
            // whole file on the heap for no reason.
            try (InputStream in = file.getInputStream()) {
                return Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ApiException(500, "Failed to save file: " + e.getMessage());
        }
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static DocumentResponse toResponse(MetadataStore.DocRow doc) {
        return DocumentResponse.builder()
                .id(doc.id())
                .filename(doc.filename())
                .fileType(doc.fileType())
                .fileSize(doc.fileSize())
                .chunkCount(doc.chunkCount())
                .status(doc.status())
                .stage(doc.stage())
                .error(doc.error())
                .createdAt(doc.createdAt())
                .knowledgeBaseId(doc.kbId())
                .build();
    }
}
