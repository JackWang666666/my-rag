package com.rag.model.dto;

import com.rag.model.GraphNode;
import com.rag.model.GraphRelation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphData {
    private List<GraphNode> nodes;
    private List<GraphRelation> edges;
}
