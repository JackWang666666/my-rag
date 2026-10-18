package com.rag.controller;

import com.rag.model.KnowledgeBase;
import com.rag.model.dto.ChunkItem;
import com.rag.model.dto.ChunkListResponse;
import com.rag.model.dto.KnowledgeBaseRequest;
import com.rag.service.EmbeddingService;
import com.rag.service.GraphService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge base CRUD and chunk browsing (docs/CONTRACT.md section 5).
 *
 * <p>All state lives in the shared SQLite store; this class used to keep a
 * {@code static ConcurrentHashMap} of knowledge bases, which vanished on restart
 * and was invisible to the Python backend.
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@Slf4j
public class KnowledgeBaseController {

    private static final Set<String> VALID_KB_TYPES = Set.of("document", "book", "project");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MetadataStore store;
    private final PermissionService permissions;
    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final GraphService graphService;

    public KnowledgeBaseController(MetadataStore store, PermissionService permissions,
                                   EmbeddingService embeddingService,
                                   RetrievalService retrievalService, GraphService graphService) {
        this.store = store;
        this.permissions = permissions;
        this.embeddingService = embeddingService;
        this.retrievalService = retrievalService;
        this.graphService = graphService;
    }

    @PostMapping
    public KnowledgeBase create(@RequestBody KnowledgeBaseRequest request) {
        CurrentUser user = CurrentUser.get();
        if (request.isGlobal()) {
            permissions.requireAdmin(user);
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw ApiException.badRequest("name must not be empty");
        }

        String kbType = request.getKbType() == null || request.getKbType().isBlank()
                ? "document"
                : request.getKbType();
        if (!VALID_KB_TYPES.contains(kbType)) {
            throw ApiException.badRequest("kbType must be one of: document, book, project");
        }

        String kbId = MetadataStore.newId();

        // The dimension is baked into the Milvus schema at creation time, so it has
        // to be read from the configured model and recorded — assuming 1536 broke
        // every non-OpenAI embedding model on the first insert.
        int dimension = embeddingService.dimension();
        try {
            retrievalService.createCollection(kbId, dimension);
        } catch (Exception e) {
            throw new ApiException(500, "Failed to create Milvus collection: " + e.getMessage());
        }

        MetadataStore.KbRow kb = store.createKb(kbId, request.getName(),
                request.getDescription(), kbType, user.username(), request.isGlobal(), dimension);
        return toResponse(kb, 0);
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        CurrentUser user = CurrentUser.get();
        Map<String, Integer> counts = store.kbDocumentCounts();
        List<KnowledgeBase> result = new ArrayList<>();
        for (MetadataStore.KbRow kb : store.listVisibleKbs(user.username(), user.role())) {
            result.add(toResponse(kb, counts.getOrDefault(kb.id(), 0)));
        }
        return result;
    }

    @GetMapping("/{kbId}")
    public KnowledgeBase get(@PathVariable String kbId) {
        MetadataStore.KbRow kb = permissions.requireKbRead(kbId, CurrentUser.get());
        return toResponse(kb, store.kbDocumentCount(kbId));
    }

    @DeleteMapping("/{kbId}")
    public Map<String, String> delete(@PathVariable String kbId) {
        permissions.requireKbWrite(kbId, CurrentUser.get());

        try {
            retrievalService.dropCollection(kbId);
        } catch (Exception e) {
            throw new ApiException(500, "Failed to drop Milvus collection: " + e.getMessage());
        }
        try {
            graphService.deleteGraph(kbId);
        } catch (Exception e) {
            // Best effort: leftover graph nodes must not block deleting the KB.
            log.warn("Graph cleanup for kb {} failed: {}", kbId, e.getMessage());
        }

        // Cascades to documents and chunks via the foreign keys.
        store.deleteKb(kbId);
        return Map.of("message", "Knowledge base deleted successfully");
    }

    /**
     * Paginated chunk listing with infix text search.
     *
     * <p>Served from SQLite rather than Milvus: Milvus 2.4's {@code like} is
     * prefix-only, so {@code %keyword%} search cannot be expressed there — the old
     * implementation issued exactly that filter and always came back empty.
     */
    @GetMapping("/{kbId}/chunks")
    public ChunkListResponse chunks(@PathVariable String kbId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(name = "pageSize",
                                            defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
                                    @RequestParam(required = false) String search) {
        permissions.requireKbRead(kbId, CurrentUser.get());

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));

        MetadataStore.ChunkPage result = store.listChunks(kbId, safePage, safeSize, search);
        List<ChunkItem> items = new ArrayList<>(result.chunks().size());
        for (MetadataStore.ChunkRow row : result.chunks()) {
            items.add(ChunkItem.builder()
                    .id(row.id())
                    .text(row.text())
                    .metadata(row.metadata())
                    .documentId(row.documentId())
                    .source(row.source())
                    .chunkIndex(row.chunkIndex())
                    .build());
        }

        return ChunkListResponse.builder()
                .chunks(items)
                .total(result.total())
                .page(safePage)
                .pageSize(safeSize)
                .build();
    }

    private static KnowledgeBase toResponse(MetadataStore.KbRow kb, int documentCount) {
        return KnowledgeBase.builder()
                .id(kb.id())
                .name(kb.name())
                .description(kb.description())
                .kbType(kb.kbType())
                .documentCount(documentCount)
                .createdAt(kb.createdAt())
                .owner(kb.owner())
                .isGlobal(kb.isGlobal())
                .build();
    }
}
