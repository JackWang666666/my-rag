package com.rag.service;

import com.rag.config.RagProperties;
import com.rag.store.MetadataStore;
import com.rag.util.Gitignore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ingestion pipeline: parse -&gt; chunk -&gt; embed -&gt; Milvus + SQLite + Neo4j.
 *
 * <p>Runs asynchronously. The upload endpoint creates the document row with
 * {@code status="processing"} and returns immediately; the frontend polls
 * {@code GET /api/documents/{id}} until it flips to {@code completed} or
 * {@code failed}. Previously the whole pipeline ran inside the request, so a
 * large PDF or a project ZIP held the HTTP connection open for minutes — and the
 * {@code processing} status the frontend already rendered was never produced.
 *
 * <p>Mirrors {@code langchain-rag/app/services/ingest.py}.
 */
@Service
@Slf4j
public class IngestService {

    /** Directories never worth indexing. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "__pycache__", "venv", ".venv",
            "dist", "build", "target", ".idea", ".vscode",
            "env", ".env", ".tox", ".mypy_cache", ".pytest_cache",
            "site-packages", ".next", "out", "coverage");

    /** Binary extensions, skipped regardless of the caller's filter. */
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            ".class", ".pyc", ".pyo", ".so", ".dll", ".dylib",
            ".exe", ".bin", ".obj", ".o", ".a", ".lib",
            ".jar", ".war", ".ear", ".zip", ".tar", ".gz",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico",
            ".svg", ".woff", ".woff2", ".ttf", ".eot",
            ".mp3", ".mp4", ".avi", ".mov", ".wav",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".lock", ".sum");

    /** Extensions scanned in a project ZIP when the caller gives no filter. */
    static final Set<String> DEFAULT_CODE_EXTENSIONS = Set.of(
            ".py", ".js", ".ts", ".tsx", ".jsx", ".java", ".go", ".rs",
            ".cpp", ".c", ".h", ".rb", ".php", ".swift", ".kt", ".scala",
            ".sql", ".yaml", ".yml", ".json", ".toml", ".xml", ".md",
            ".txt", ".html", ".css", ".scss");

    /** Bytes inspected when deciding whether a file is binary. */
    private static final int BINARY_SNIFF_BYTES = 8192;

    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final GraphService graphService;
    private final MetadataStore store;
    private final RagProperties props;

    public IngestService(DocumentService documentService, EmbeddingService embeddingService,
                         RetrievalService retrievalService, GraphService graphService,
                         MetadataStore store, RagProperties props) {
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.retrievalService = retrievalService;
        this.graphService = graphService;
        this.store = store;
        this.props = props;
    }

    // --- Single file ------------------------------------------------------

    /** Background task: ingest one uploaded file, then delete it. */
    @Async("ingestExecutor")
    public void ingestFile(String kbId, String docId, Path filePath, String fileType,
                           String filename) {
        try {
            store.setDocumentStage(docId, "parsing");
            List<String> texts = documentService.parseAndSplit(filePath, fileType);
            if (texts.isEmpty()) {
                store.markDocumentFailed(docId, "No content could be extracted from the file");
                return;
            }

            List<Map<String, Object>> metadatas = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", filename);
                metadata.put("file_type", fileType);
                metadata.put("chunk_index", i);
                metadata.put("document_id", docId);
                metadatas.add(metadata);
            }

            int count = persist(kbId, docId, texts, metadatas);
            store.markDocumentCompleted(docId, count);
            log.info("Ingested '{}' ({} chunks) into kb {}", filename, count, kbId);
        } catch (Exception e) {
            log.error("Ingestion failed for document {}", docId, e);
            store.markDocumentFailed(docId, e.getMessage());
        } finally {
            deleteQuietly(filePath);
        }
    }

    // --- Project ZIP ------------------------------------------------------

    /** Background task: extract a project ZIP and ingest every matching code file. */
    @Async("ingestExecutor")
    public void ingestProject(String kbId, String docId, Path zipPath, Path tempDir,
                              String fileExtensions) {
        try {
            store.setDocumentStage(docId, "parsing");
            Path extractDir = tempDir.resolve("extracted");
            Files.createDirectories(extractDir);

            try {
                safeExtract(zipPath, extractDir);
            } catch (IllegalArgumentException e) {
                store.markDocumentFailed(docId, e.getMessage());
                return;
            } catch (IOException e) {
                store.markDocumentFailed(docId, "Invalid ZIP file: " + e.getMessage());
                return;
            }

            Path projectRoot = unwrapSingleDirectory(extractDir);
            List<String> codeFiles = scanProjectFiles(projectRoot,
                    parseExtensionFilter(fileExtensions));
            if (codeFiles.isEmpty()) {
                store.markDocumentFailed(docId,
                        "No code files found in the ZIP archive matching the criteria");
                return;
            }

            List<String> texts = new ArrayList<>();
            List<Map<String, Object>> metadatas = new ArrayList<>();
            chunkProject(projectRoot, codeFiles, docId, texts, metadatas);
            if (texts.size() <= 1) {
                store.markDocumentFailed(docId,
                        "No content could be extracted from the project files");
                return;
            }

            int count = persist(kbId, docId, texts, metadatas);
            store.markDocumentCompleted(docId, count);
            log.info("Imported project into kb {}: {} files, {} chunks",
                    kbId, codeFiles.size(), count);
        } catch (Exception e) {
            log.error("Project import failed for document {}", docId, e);
            store.markDocumentFailed(docId, e.getMessage());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Extract a ZIP, refusing entries that escape the target directory.
     *
     * <p>Naively resolving {@code entry.getName()} against the output directory
     * honours {@code ../} and absolute paths, so a hostile archive could overwrite
     * arbitrary files ("zip slip").
     */
    static void safeExtract(Path zipPath, Path extractDir) throws IOException {
        Path root = extractDir.toRealPath();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || name.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                String normalized = name.replace('\\', '/');
                if (normalized.startsWith("/") || List.of(normalized.split("/")).contains("..")) {
                    throw new IllegalArgumentException("Unsafe path in ZIP archive: " + name);
                }
                Path target;
                try {
                    target = root.resolve(normalized).normalize();
                } catch (RuntimeException e) {
                    // e.g. InvalidPathException for a name illegal on this platform.
                    throw new IllegalArgumentException("Unsafe path in ZIP archive: " + name);
                }
                // Authoritative check: an absolute or traversing name lands outside root.
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Unsafe path in ZIP archive: " + name);
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Handle the common "everything inside one top-level folder" ZIP layout. */
    private static Path unwrapSingleDirectory(Path extractDir) throws IOException {
        try (var children = Files.list(extractDir)) {
            List<Path> items = children.toList();
            if (items.size() == 1 && Files.isDirectory(items.get(0))) {
                return items.get(0);
            }
        }
        return extractDir;
    }

    /** {@code ".py,js"} -&gt; {@code {".py", ".js"}}. Blank input gives the defaults. */
    static Set<String> parseExtensionFilter(String fileExtensions) {
        if (fileExtensions == null || fileExtensions.isBlank()) {
            return DEFAULT_CODE_EXTENSIONS;
        }
        Set<String> exts = new LinkedHashSet<>();
        for (String raw : fileExtensions.split(",")) {
            String ext = raw.strip().toLowerCase(Locale.ROOT);
            if (ext.isEmpty()) {
                continue;
            }
            exts.add(ext.startsWith(".") ? ext : "." + ext);
        }
        return exts.isEmpty() ? DEFAULT_CODE_EXTENSIONS : exts;
    }

    /** Relative paths (forward slashes) of the code files worth indexing. */
    static List<String> scanProjectFiles(Path projectRoot, Set<String> allowedExtensions)
            throws IOException {
        List<Gitignore.Rule> rules = Gitignore.parse(projectRoot);
        List<String> codeFiles = new ArrayList<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(projectRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Gitignore.isIgnored(relativize(projectRoot, dir), rules, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String ext = extensionOf(file.getFileName().toString());
                if (SKIP_EXTENSIONS.contains(ext) || !allowedExtensions.contains(ext)) {
                    return FileVisitResult.CONTINUE;
                }
                String relPath = relativize(projectRoot, file);
                if (Gitignore.isIgnored(relPath, rules, false) || isBinary(file)) {
                    return FileVisitResult.CONTINUE;
                }
                codeFiles.add(relPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // An unreadable file is not a reason to abandon the whole import.
                return FileVisitResult.CONTINUE;
            }
        });

        return codeFiles;
    }

    /** Chunk every code file, prefixing each chunk with its path, plus a file tree. */
    private void chunkProject(Path projectRoot, List<String> codeFiles, String docId,
                              List<String> texts, List<Map<String, Object>> metadatas) {
        texts.add("Project Structure:\n\n" + fileTree(projectRoot, codeFiles));
        Map<String, Object> treeMetadata = new HashMap<>();
        treeMetadata.put("source", "project_structure");
        treeMetadata.put("file_type", "tree");
        treeMetadata.put("chunk_index", 0);
        treeMetadata.put("document_id", docId);
        treeMetadata.put("is_project_structure", true);
        metadatas.add(treeMetadata);

        int chunkIndex = 1;
        for (String relPath : codeFiles) {
            String ext = extensionOf(relPath);
            try {
                String content = Files.readString(projectRoot.resolve(relPath));
                if (content.isBlank()) {
                    continue;
                }
                for (String chunk : documentService.split(content, ext)) {
                    // The path prefix is what lets a retrieved snippet be attributed
                    // to a file in the answer.
                    texts.add("File: " + relPath + "\n\n" + chunk);
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", relPath);
                    metadata.put("file_type", ext);
                    metadata.put("chunk_index", chunkIndex);
                    metadata.put("document_id", docId);
                    metadatas.add(metadata);
                    chunkIndex++;
                }
            } catch (Exception e) {
                log.debug("Skipping unreadable file {}: {}", relPath, e.getMessage());
            }
        }
    }

    private static String fileTree(Path projectRoot, List<String> files) {
        Path name = projectRoot.getFileName();
        StringBuilder tree = new StringBuilder(name == null ? "project/" : name + "/");
        for (String file : new TreeSet<>(files)) {
            String[] parts = file.split("/");
            tree.append('\n').append("  ".repeat(parts.length - 1)).append(parts[parts.length - 1]);
        }
        return tree.toString();
    }

    // --- Shared tail ------------------------------------------------------

    /** Embed, write to Milvus, mirror into SQLite, extract the graph. */
    private int persist(String kbId, String docId, List<String> texts,
                        List<Map<String, Object>> metadatas) {
        store.setDocumentStage(docId, "embedding");
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        List<RetrievalService.VectorChunk> vectorChunks = new ArrayList<>(texts.size());
        List<MetadataStore.ChunkInput> chunkRows = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i++) {
            String chunkId = MetadataStore.newId();
            Map<String, Object> metadata = metadatas.get(i);
            vectorChunks.add(RetrievalService.VectorChunk.builder()
                    .id(chunkId)
                    .documentId(docId)
                    .text(texts.get(i))
                    .metadata(metadata)
                    .embedding(embeddings.get(i))
                    .build());
            chunkRows.add(new MetadataStore.ChunkInput(chunkId,
                    asInt(metadata.get("chunk_index"), i),
                    String.valueOf(metadata.getOrDefault("source", "")),
                    texts.get(i), metadata));
        }

        retrievalService.insert(kbId, vectorChunks);
        store.insertChunks(kbId, docId, chunkRows);
        store.setDocumentStage(docId, "graph");
        extractGraph(kbId, texts);
        return texts.size();
    }

    /**
     * Best-effort entity extraction into Neo4j.
     *
     * <p>Bounded by {@code GRAPH_MAX_CHUNKS}: unbounded means one LLM call per
     * chunk, but the old hardcoded limit of 3 chunks left the graph near-empty for
     * anything longer than a memo.
     */
    private void extractGraph(String kbId, List<String> texts) {
        int limit = Math.max(0, props.getGraphMaxChunks());
        if (limit == 0) {
            return;
        }
        for (String text : texts.subList(0, Math.min(limit, texts.size()))) {
            try {
                graphService.extractAndStore(kbId, text);
            } catch (Exception e) {
                log.warn("Entity extraction failed for a chunk: {}", e.getMessage());
            }
        }
    }

    // --- Small helpers ----------------------------------------------------

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String relativize(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static boolean isBinary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(BINARY_SNIFF_BYTES);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", path, e.getMessage());
        }
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp file is not worth failing over.
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean up {}: {}", dir, e.getMessage());
        }
    }
}
