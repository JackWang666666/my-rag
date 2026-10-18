package com.rag.service;

import com.rag.config.RagProperties;
import com.rag.util.TextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text extraction and chunking.
 *
 * <p>Tika handles the binary formats (PDF, DOCX, XLSX); plain text and source
 * files are read directly, because routing them through Tika only risks a charset
 * mis-detection.
 */
@Service
@Slf4j
public class DocumentService {

    /** File types Tika should handle rather than being read as text. */
    private static final Set<String> BINARY_TYPES = Set.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt", ".odt", ".rtf", ".epub");

    private final Tika tika = new Tika();
    private final RagProperties props;

    public DocumentService(RagProperties props) {
        this.props = props;
        // Tika's default write limit is 100 000 characters; past that
        // parseToString throws and a long PDF fails to ingest at all.
        this.tika.setMaxStringLength(-1);
    }

    /**
     * Extract the full text of a file.
     *
     * @param fileType extension including the dot, lower-cased by the caller or not
     */
    public String extractText(Path path, String fileType) {
        String ext = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        try {
            if (BINARY_TYPES.contains(ext)) {
                return parseWithTika(path);
            }
            // errors=ignore equivalent: never fail ingestion over one bad byte.
            return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Direct read of '{}' failed ({}), falling back to Tika", path, e.getMessage());
            try {
                return parseWithTika(path);
            } catch (Exception fallback) {
                throw new RuntimeException("Failed to extract text: " + fallback.getMessage(), fallback);
            }
        }
    }

    private String parseWithTika(Path path) throws Exception {
        Metadata metadata = new Metadata();
        // Metadata.RESOURCE_NAME_KEY was removed in Tika 2.x; the constant now lives
        // on TikaCoreProperties. Referencing the old one is why this class did not
        // compile.
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        try (InputStream in = Files.newInputStream(path)) {
            // -1 = no write limit, see setMaxStringLength above.
            return tika.parseToString(in, metadata, -1);
        }
    }

    /** Extract and chunk in one step, using the configured chunk size/overlap. */
    public List<String> parseAndSplit(Path path, String fileType) {
        String text = extractText(path, fileType);
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        return split(text, fileType);
    }

    /** Chunk already-extracted text with the configured chunk size/overlap. */
    public List<String> split(String text, String fileType) {
        return TextSplitter.split(text, fileType, props.getChunkSize(), props.getChunkOverlap());
    }
}
