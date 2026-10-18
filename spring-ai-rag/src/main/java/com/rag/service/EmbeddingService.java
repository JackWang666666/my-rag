package com.rag.service;

import com.rag.config.EmbeddingModelRegistry;
import com.rag.config.RagProperties;
import com.rag.config.SettingsStore;
import com.rag.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Text embedding.
 *
 * <p>Spring AI 1.0.0-M1 returns embeddings as {@code List<Double>}; Milvus wants
 * {@code float[]}. The conversion happens here rather than being repeated (and
 * mistyped — the previous version assigned {@code List<Double>} straight to
 * {@code float[]} and did not compile) at every call site.
 *
 * <p>The provider comes from the settings store, symmetrically with
 * {@link LlmService}. Note that changing it only makes sense for knowledge bases
 * created afterwards; see {@link EmbeddingModelRegistry}.
 */
@Service
@Slf4j
public class EmbeddingService {

    private final EmbeddingModelRegistry registry;
    private final SettingsStore settings;
    private final RagProperties props;

    public EmbeddingService(EmbeddingModelRegistry registry, SettingsStore settings,
                            RagProperties props) {
        this.registry = registry;
        this.settings = settings;
        this.props = props;
    }

    private EmbeddingModel model() {
        return registry.resolve(settings.current().getEmbeddingProvider());
    }

    /**
     * Vector width of the configured model.
     *
     * <p>Used as a knowledge base's {@code embedding_dim} at creation time instead
     * of a hardcoded 1536, which silently broke every non-OpenAI embedding model
     * (bge-m3 is 1024) with a dimension-mismatch error on the first insert.
     *
     * <p>Spring AI derives the width by embedding a probe string, so this is a live
     * call to the provider and fails when the provider is unreachable. That failure
     * arrives as whatever the transport threw — creating a knowledge base with no
     * embedding provider running once surfaced as a 400 reading
     * {@code "Self-suppression not permitted"} — so it is translated into something
     * that names the actual problem.
     */
    public int dimension() {
        try {
            return model().dimensions();
        } catch (Exception e) {
            String provider = settings.current().getEmbeddingProvider();
            log.error("Could not determine the embedding dimension from provider '{}'",
                    provider, e);
            throw new ApiException(503, "Embedding provider '" + provider + "' is not"
                    + " reachable, so the vector dimension could not be determined."
                    + " Check EMBEDDING_PROVIDER and its credentials.");
        }
    }

    public float[] embed(String text) {
        EmbeddingResponse response = model().embedForResponse(List.of(text));
        return response.getResults().get(0).getOutput();
    }

    /**
     * Embed many texts, in batches.
     *
     * <p>A project ZIP can produce thousands of chunks; one request with all of
     * them either exceeds the provider's payload limit or times out. Batch size
     * comes from {@code EMBEDDING_BATCH_SIZE}.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        int batchSize = Math.max(1, props.getEmbeddingBatchSize());
        List<float[]> results = new ArrayList<>(texts.size());
        // Resolved once: a settings change mid-ingest would mix vector spaces
        // inside a single document.
        EmbeddingModel model = model();

        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            EmbeddingResponse response = model.embedForResponse(
                    new ArrayList<>(texts.subList(start, end)));
            response.getResults().forEach(result -> results.add(result.getOutput()));
            log.debug("Embedded {}/{} chunks", end, texts.size());
        }

        return results;
    }

}
