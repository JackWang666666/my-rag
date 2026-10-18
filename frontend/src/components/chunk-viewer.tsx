"use client";

import React, { useState, useEffect, useCallback } from "react";
import {
  Search,
  FileText,
  ChevronDown,
  ChevronUp,
  Loader2,
  AlertCircle,
} from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { listChunks } from "@/lib/api";
import { cn } from "@/lib/utils";
import type { ChunkItem } from "@/types";

interface ChunkViewerProps {
  knowledgeBaseId: string;
}

export function ChunkViewer({ knowledgeBaseId }: ChunkViewerProps) {
  const [chunks, setChunks] = useState<ChunkItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(20);
  const [search, setSearch] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedChunks, setExpandedChunks] = useState<Set<string>>(new Set());

  const loadChunks = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    const result = await listChunks(knowledgeBaseId, page, pageSize, search || undefined);
    if (result.success && result.data) {
      setChunks(result.data.chunks);
      setTotal(result.data.total);
    } else {
      setError(result.error || "Failed to load chunks");
      setChunks([]);
    }
    setIsLoading(false);
  }, [knowledgeBaseId, page, pageSize, search]);

  useEffect(() => {
    loadChunks();
  }, [loadChunks]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(1);
    setSearch(searchInput);
  };

  const toggleExpand = (id: string) => {
    setExpandedChunks((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const totalPages = Math.ceil(total / pageSize);

  return (
    <div className="space-y-4">
      {/* Search bar */}
      <form onSubmit={handleSearch} className="flex gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search chunks by content..."
            className="pl-9"
          />
        </div>
        <Button type="submit" variant="secondary">
          Search
        </Button>
      </form>

      {/* Stats */}
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          {total} chunk{total !== 1 ? "s" : ""} total
          {search && (
            <span>
              {" "}
              &middot; filtered by &quot;{search}&quot;
              <button
                onClick={() => {
                  setSearch("");
                  setSearchInput("");
                  setPage(1);
                }}
                className="ml-1 text-primary hover:underline"
              >
                clear
              </button>
            </span>
          )}
        </p>
        {totalPages > 1 && (
          <p className="text-sm text-muted-foreground">
            Page {page} of {totalPages}
          </p>
        )}
      </div>

      {/* Loading state */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* Error state */}
      {error && !isLoading && (
        <div className="flex items-center gap-2 p-4 rounded-lg border border-destructive/30 bg-destructive/5 text-sm">
          <AlertCircle className="h-4 w-4 text-destructive flex-shrink-0" />
          <span className="text-destructive">{error}</span>
        </div>
      )}

      {/* Empty state */}
      {!isLoading && !error && chunks.length === 0 && (
        <div className="text-center py-12">
          <FileText className="h-10 w-10 text-muted-foreground mx-auto mb-3" />
          <p className="text-sm text-muted-foreground">
            {search ? "No chunks match your search" : "No chunks stored yet"}
          </p>
        </div>
      )}

      {/* Chunks list */}
      {!isLoading && chunks.length > 0 && (
        <div className="space-y-3">
          {chunks.map((chunk) => {
            const isExpanded = expandedChunks.has(chunk.id);
            return (
              <div
                key={chunk.id}
                className="rounded-lg border border-border hover:border-border/80 transition-colors overflow-hidden"
              >
                <div
                  className="p-4 cursor-pointer hover:bg-muted/20 transition-colors"
                  onClick={() => toggleExpand(chunk.id)}
                >
                  <div className="flex items-start gap-3">
                    <FileText className="h-4 w-4 text-muted-foreground mt-0.5 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                        <span className="text-xs font-medium text-foreground">
                          {chunk.source || "Unknown source"}
                        </span>
                        {chunk.metadata &&
                          Object.entries(chunk.metadata)
                            // `source` and the document id are already shown above.
                            // Milvus stores the id snake_cased inside the metadata
                            // JSON, so both spellings have to be excluded.
                            .filter(
                              ([key]) =>
                                key !== "source" &&
                                key !== "documentId" &&
                                key !== "document_id"
                            )
                            .slice(0, 3)
                            .map(([key, value]) => (
                              <Badge
                                key={key}
                                variant="secondary"
                                className="text-[10px] px-1.5 py-0"
                              >
                                {key}: {String(value).slice(0, 20)}
                              </Badge>
                            ))}
                      </div>
                      <p
                        className={cn(
                          "text-sm text-muted-foreground leading-relaxed",
                          !isExpanded && "line-clamp-2"
                        )}
                      >
                        {chunk.text}
                      </p>
                    </div>
                    <button className="flex-shrink-0 text-muted-foreground">
                      {isExpanded ? (
                        <ChevronUp className="h-4 w-4" />
                      ) : (
                        <ChevronDown className="h-4 w-4" />
                      )}
                    </button>
                  </div>
                </div>
                {isExpanded && (
                  <div className="px-4 pb-4 pt-0 border-t border-border/50 bg-muted/10">
                    <div className="pt-3 space-y-2">
                      <p className="text-sm whitespace-pre-wrap leading-relaxed text-foreground/90">
                        {chunk.text}
                      </p>
                      {chunk.metadata && Object.keys(chunk.metadata).length > 0 && (
                        <div className="pt-2 border-t border-border/50">
                          <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider mb-1.5">
                            Metadata
                          </p>
                          <div className="grid grid-cols-2 gap-1">
                            {Object.entries(chunk.metadata).map(([key, value]) => (
                              <div key={key} className="text-xs text-muted-foreground">
                                <span className="font-medium">{key}:</span>{" "}
                                {String(value)}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.max(1, p - 1))}
            disabled={page <= 1 || isLoading}
          >
            Previous
          </Button>
          <div className="flex items-center gap-1">
            {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
              let pageNum: number;
              if (totalPages <= 5) {
                pageNum = i + 1;
              } else if (page <= 3) {
                pageNum = i + 1;
              } else if (page >= totalPages - 2) {
                pageNum = totalPages - 4 + i;
              } else {
                pageNum = page - 2 + i;
              }
              return (
                <Button
                  key={pageNum}
                  variant={page === pageNum ? "default" : "ghost"}
                  size="sm"
                  className="w-8 h-8"
                  onClick={() => setPage(pageNum)}
                  disabled={isLoading}
                >
                  {pageNum}
                </Button>
              );
            })}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            disabled={page >= totalPages || isLoading}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  );
}

