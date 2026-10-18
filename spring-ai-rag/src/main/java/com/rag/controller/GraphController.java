package com.rag.controller;

import com.rag.model.dto.GraphData;
import com.rag.model.dto.GraphQueryRequest;
import com.rag.service.GraphService;
import com.rag.service.PermissionService;
import com.rag.web.ApiException;
import com.rag.web.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;
    private final PermissionService permissions;

    /** Both endpoints return {@code {nodes, edges}} (contract section 5). */
    @GetMapping("/{kbId}")
    public ResponseEntity<GraphData> getGraph(@PathVariable String kbId) {
        permissions.requireKbRead(kbId, CurrentUser.get());
        return ResponseEntity.ok(graphService.getFullGraph(kbId));
    }

    @PostMapping("/query")
    public ResponseEntity<GraphData> queryEntityRelationships(@RequestBody GraphQueryRequest request) {
        permissions.requireKbRead(request.getKnowledgeBaseId(), CurrentUser.get());

        String entity = request.getEntity() == null ? "" : request.getEntity().trim();
        if (entity.isEmpty()) {
            throw ApiException.badRequest("Entity name cannot be empty");
        }
        return ResponseEntity.ok(graphService.queryGraph(request.getKnowledgeBaseId(), entity));
    }
}
