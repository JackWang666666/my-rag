"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import dynamic from "next/dynamic";
import { Search, Loader2, Info, X, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { listKnowledgeBases, getGraphData, queryGraph } from "@/lib/api";
import type { KnowledgeBase, GraphData } from "@/types";

/**
 * The force graph renders to a canvas and touches `window`, so it can only load
 * in the browser.
 *
 * The wrapper exists because `next/dynamic` does **not** forward refs: passing
 * `ref` straight to the dynamic component left `graphRef.current` null forever,
 * so clicking a node never centred or zoomed it. Handing the ref through an
 * ordinary `innerRef` prop is what makes the imperative API reachable.
 */
const ForceGraph2D = dynamic(
  async () => {
    const mod = await import("react-force-graph-2d");
    const Inner = mod.default as any;
    const Wrapped = ({ innerRef, ...props }: any) => <Inner ref={innerRef} {...props} />;
    Wrapped.displayName = "ForceGraph2DWithRef";
    return Wrapped;
  },
  {
    ssr: false,
    loading: () => (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    ),
  }
);

interface GraphNodeData {
  id: string;
  label: string;
  type: string;
  properties?: Record<string, unknown>;
  val?: number;
  color?: string;
  /** Written by the force simulation, not by us. */
  x?: number;
  y?: number;
}

interface GraphLinkData {
  source: string;
  target: string;
  label: string;
  weight?: number;
}

const NODE_COLORS: Record<string, string> = {
  entity: "#3b82f6",
  concept: "#8b5cf6",
  person: "#ec4899",
  organization: "#f59e0b",
  location: "#10b981",
  event: "#ef4444",
  document: "#6366f1",
  default: "#64748b",
};

export default function GraphPage() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKb, setSelectedKb] = useState<string>("");
  const [graphData, setGraphData] = useState<{
    nodes: GraphNodeData[];
    links: GraphLinkData[];
  }>({ nodes: [], links: [] });
  const [isLoading, setIsLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedNode, setSelectedNode] = useState<GraphNodeData | null>(null);
  const [highlightNodes, setHighlightNodes] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const graphRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  // The canvas needs explicit pixel dimensions. Measuring the container beats the
  // previous `window.innerWidth - 320`, which guessed the sidebar width and never
  // updated when the window was resized.
  const [size, setSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => {
      setSize({
        width: Math.floor(entry.contentRect.width),
        height: Math.floor(entry.contentRect.height),
      });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const transformGraphData = useCallback((data: GraphData) => {
    const nodes: GraphNodeData[] = data.nodes.map((node) => {
      // `type` is optional in the contract; Neo4j nodes written before typing was
      // added have none.
      const type = node.type || "entity";
      return {
        id: node.id,
        label: node.label,
        type,
        properties: node.properties,
        val: 5,
        color: NODE_COLORS[type.toLowerCase()] || NODE_COLORS.default,
      };
    });

    const links: GraphLinkData[] = data.edges.map((edge) => ({
      source: edge.source,
      target: edge.target,
      // `relation` is the contract field; `label` is only a display alias.
      label: edge.relation || edge.label || "",
      weight: edge.weight,
    }));

    setGraphData({ nodes, links });
  }, []);

  const loadGraphData = useCallback(
    async (kbId: string) => {
      setIsLoading(true);
      setError(null);
      const result = await getGraphData(kbId);
      if (result.success && result.data) {
        transformGraphData(result.data);
      } else {
        setGraphData({ nodes: [], links: [] });
        setError(result.error ?? "Failed to load the knowledge graph");
      }
      setIsLoading(false);
    },
    [transformGraphData]
  );

  const loadKnowledgeBases = useCallback(async () => {
    const result = await listKnowledgeBases();
    if (result.success && result.data) {
      setKnowledgeBases(result.data);
      if (result.data.length > 0) {
        setSelectedKb(result.data[0].id);
      }
    } else {
      setError(result.error ?? "Failed to load knowledge bases");
    }
  }, []);

  useEffect(() => {
    loadKnowledgeBases();
  }, [loadKnowledgeBases]);

  useEffect(() => {
    if (selectedKb) {
      setSelectedNode(null);
      setHighlightNodes(new Set());
      loadGraphData(selectedKb);
    }
  }, [selectedKb, loadGraphData]);

  const handleSearch = async () => {
    if (!searchQuery.trim() || !selectedKb) return;

    setIsLoading(true);
    setError(null);
    const result = await queryGraph(selectedKb, searchQuery);
    if (result.success && result.data) {
      transformGraphData(result.data);

      // Highlight matched nodes
      const matchedIds = new Set(result.data.nodes.map((n) => n.id));
      setHighlightNodes(matchedIds);
      if (result.data.nodes.length === 0) {
        setError(`No entity matching "${searchQuery}" is in this graph.`);
      }
    } else {
      setError(result.error ?? "Graph query failed");
    }
    setIsLoading(false);
  };

  const handleClearSearch = () => {
    setSearchQuery("");
    setHighlightNodes(new Set());
    setError(null);
    if (selectedKb) {
      loadGraphData(selectedKb);
    }
  };

  const handleNodeClick = useCallback((node: GraphNodeData) => {
    setSelectedNode(node);
    // Center on the clicked node. `x`/`y` come from the force simulation, so they
    // are only set once it has ticked at least once.
    if (graphRef.current && node.x != null && node.y != null) {
      graphRef.current.centerAt(node.x, node.y, 1000);
      graphRef.current.zoom(2, 1000);
    }
  }, []);

  const nodeCanvasObject = useCallback(
    (node: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const label = node.label;
      const fontSize = 12 / globalScale;
      ctx.font = `${fontSize}px Inter, sans-serif`;

      // Node circle
      const size = node.val || 5;
      ctx.beginPath();
      ctx.arc(node.x, node.y, size, 0, 2 * Math.PI);

      // Highlight effect
      if (highlightNodes.has(node.id)) {
        ctx.shadowColor = node.color;
        ctx.shadowBlur = 15;
      }

      ctx.fillStyle = node.color || NODE_COLORS.default;
      ctx.fill();
      ctx.shadowBlur = 0;

      // Border for selected node
      if (selectedNode?.id === node.id) {
        ctx.strokeStyle = "#ffffff";
        ctx.lineWidth = 2 / globalScale;
        ctx.stroke();
      }

      // Label
      if (globalScale > 0.7) {
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        ctx.fillStyle = "rgba(255, 255, 255, 0.9)";
        ctx.fillText(label, node.x, node.y + size + 2);
      }
    },
    [highlightNodes, selectedNode]
  );

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="border-b border-border px-6 py-4 flex items-center gap-4 flex-wrap">
        <div className="flex items-center gap-2 flex-shrink-0">
          <label className="text-sm font-medium text-muted-foreground">
            Knowledge Base:
          </label>
          <Select
            options={knowledgeBases.map((kb) => ({
              value: kb.id,
              label: kb.name,
            }))}
            value={selectedKb}
            onChange={(e) => setSelectedKb(e.target.value)}
            placeholder="Select a knowledge base"
            className="w-48"
          />
        </div>

        <div className="flex-1 flex items-center gap-2 max-w-md">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              className="pl-9"
              placeholder="Search entities..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
            />
          </div>
          <Button onClick={handleSearch} disabled={isLoading} size="sm">
            Search
          </Button>
          {highlightNodes.size > 0 && (
            <Button variant="ghost" size="sm" onClick={handleClearSearch}>
              Clear
            </Button>
          )}
        </div>

        {/* Legend */}
        <div className="flex items-center gap-2 flex-wrap">
          {Object.entries(NODE_COLORS)
            .filter(([key]) => key !== "default")
            .map(([type, color]) => (
              <div key={type} className="flex items-center gap-1">
                <div
                  className="w-3 h-3 rounded-full"
                  style={{ backgroundColor: color }}
                />
                <span className="text-xs text-muted-foreground capitalize">
                  {type}
                </span>
              </div>
            ))}
        </div>
      </div>

      {/* Graph */}
      <div ref={containerRef} className="flex-1 relative bg-background overflow-hidden">
        {error && (
          <div className="absolute top-4 left-4 right-4 z-30 flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
            <span className="flex-1 break-words">{error}</span>
            <button
              type="button"
              onClick={() => setError(null)}
              className="flex-shrink-0 opacity-70 hover:opacity-100"
              aria-label="Dismiss error"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        )}

        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center z-10 bg-background/80">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          </div>
        )}

        {graphData.nodes.length === 0 && !isLoading ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center space-y-3">
              <Info className="h-12 w-12 text-muted-foreground mx-auto" />
              <h3 className="text-lg font-medium">No graph data</h3>
              <p className="text-sm text-muted-foreground max-w-sm">
                Select a knowledge base with processed documents to view the
                knowledge graph. Entities and their relationships will appear
                here.
              </p>
            </div>
          </div>
        ) : size.width > 0 ? (
          <ForceGraph2D
            innerRef={graphRef}
            graphData={graphData}
            nodeCanvasObject={nodeCanvasObject}
            nodePointerAreaPaint={(node: any, color: string, ctx: CanvasRenderingContext2D) => {
              const size = node.val || 5;
              ctx.beginPath();
              ctx.arc(node.x, node.y, size + 2, 0, 2 * Math.PI);
              ctx.fillStyle = color;
              ctx.fill();
            }}
            onNodeClick={handleNodeClick}
            linkColor={() => "rgba(100, 116, 139, 0.3)"}
            linkWidth={(link: any) => (link.weight || 1) * 0.5}
            linkDirectionalParticles={2}
            linkDirectionalParticleWidth={2}
            linkDirectionalParticleSpeed={0.005}
            backgroundColor="transparent"
            width={size.width}
            height={size.height}
          />
        ) : null}

        {/* Node details panel */}
        {selectedNode && (
          <div className="absolute top-4 right-4 w-72 bg-card border border-border rounded-lg shadow-lg p-4 z-20">
            <div className="flex items-start justify-between mb-3">
              <div>
                <h3 className="font-semibold text-sm">{selectedNode.label}</h3>
                <Badge variant="secondary" className="mt-1 capitalize text-xs">
                  {selectedNode.type}
                </Badge>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                onClick={() => setSelectedNode(null)}
              >
                <X className="h-3 w-3" />
              </Button>
            </div>
            {selectedNode.properties &&
              Object.keys(selectedNode.properties).length > 0 && (
                <div className="space-y-2 border-t border-border pt-3">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                    Properties
                  </p>
                  {Object.entries(selectedNode.properties).map(
                    ([key, value]) => (
                      <div key={key} className="text-xs">
                        <span className="font-medium">{key}:</span>{" "}
                        {/* Property values are `unknown` in the contract — Neo4j
                            can return numbers and lists, not just strings. */}
                        <span className="text-muted-foreground break-words">
                          {typeof value === "object" && value !== null
                            ? JSON.stringify(value)
                            : String(value)}
                        </span>
                      </div>
                    )
                  )}
                </div>
              )}
          </div>
        )}
      </div>
    </div>
  );
}
