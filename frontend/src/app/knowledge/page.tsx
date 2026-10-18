"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  Plus,
  Database,
  FileText,
  Trash2,
  ArrowLeft,
  Calendar,
  Hash,
  Loader2,
  Upload,
  BookOpen,
  Code,
  FolderArchive,
  Eye,
  Network,
  Globe,
  Lock,
  AlertCircle,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";
import { FileUpload } from "@/components/file-upload";
import { ChunkViewer } from "@/components/chunk-viewer";
import {
  listKnowledgeBases,
  createKnowledgeBase,
  deleteKnowledgeBase,
  listDocuments,
  uploadDocument,
  deleteDocument,
  importProject,
  getGraphData,
  waitForDocument,
} from "@/lib/api";
import type { KbType, KnowledgeBase, Document, DocumentStage, GraphData } from "@/types";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";

type DetailTab = "documents" | "content" | "graph";

const STAGE_LABELS: Record<DocumentStage, string> = {
  parsing: "解析文件中",
  embedding: "生成向量中",
  graph: "构建知识图谱中",
};

const KB_TYPE_CONFIG: Record<
  KbType,
  { icon: React.ReactNode; label: string; description: string; color: string }
> = {
  document: {
    icon: <FileText className="h-5 w-5" />,
    label: "Document",
    description: "Import knowledge documents for Q&A",
    color: "text-blue-500",
  },
  book: {
    icon: <BookOpen className="h-5 w-5" />,
    label: "Book",
    description: "Import a complete book or long document",
    color: "text-amber-500",
  },
  project: {
    icon: <Code className="h-5 w-5" />,
    label: "Project",
    description: "Import a code project for analysis",
    color: "text-emerald-500",
  },
};

function getKbIcon(kbType: KbType, className?: string) {
  const config = KB_TYPE_CONFIG[kbType] || KB_TYPE_CONFIG.document;
  return (
    <span className={cn(config.color, className)}>
      {kbType === "document" && <FileText className="h-5 w-5" />}
      {kbType === "book" && <BookOpen className="h-5 w-5" />}
      {kbType === "project" && <Code className="h-5 w-5" />}
    </span>
  );
}

export default function KnowledgePage() {
  const { isAdmin } = useAuth();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKb, setSelectedKb] = useState<KnowledgeBase | null>(null);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isUploadOpen, setIsUploadOpen] = useState(false);
  const [newKbName, setNewKbName] = useState("");
  const [newKbDescription, setNewKbDescription] = useState("");
  const [newKbType, setNewKbType] = useState<KbType>("document");
  const [newKbIsGlobal, setNewKbIsGlobal] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<DetailTab>("documents");
  const [importingProject, setImportingProject] = useState(false);
  const [importProgress, setImportProgress] = useState(0);
  const [graphData, setGraphData] = useState<GraphData | null>(null);
  const [graphLoading, setGraphLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One controller per detail view; every ingestion poll started while that view
  // is open shares it, so leaving the view stops all of them at once.
  const pollAbortRef = useRef<AbortController | null>(null);

  // Stop polling on unmount, otherwise the pending fetches keep calling setState.
  useEffect(() => {
    return () => pollAbortRef.current?.abort();
  }, []);

  const loadKnowledgeBases = useCallback(async () => {
    setIsLoading(true);
    const result = await listKnowledgeBases();
    if (result.success && result.data) {
      setKnowledgeBases(result.data);
    } else {
      setError(result.error ?? "Failed to load knowledge bases");
    }
    setIsLoading(false);
  }, []);

  useEffect(() => {
    loadKnowledgeBases();
  }, [loadKnowledgeBases]);

  const loadDocuments = useCallback(async (kbId: string) => {
    const result = await listDocuments(kbId);
    if (result.success && result.data) {
      setDocuments(result.data);
    } else {
      setError(result.error ?? "Failed to load documents");
    }
  }, []);

  const loadGraph = async (kbId: string) => {
    setGraphLoading(true);
    const result = await getGraphData(kbId);
    if (result.success && result.data) {
      setGraphData(result.data);
    } else if (!result.success) {
      setError(result.error ?? "Failed to load the knowledge graph");
    }
    setGraphLoading(false);
  };

  const handleSelectKb = (kb: KnowledgeBase) => {
    pollAbortRef.current?.abort();
    pollAbortRef.current = new AbortController();
    setSelectedKb(kb);
    setActiveTab("documents");
    setError(null);
    loadDocuments(kb.id);
  };

  const handleBack = () => {
    pollAbortRef.current?.abort();
    pollAbortRef.current = null;
    setSelectedKb(null);
    setDocuments([]);
    setGraphData(null);
    setActiveTab("documents");
    setError(null);
  };

  const handleTabChange = (tab: DetailTab) => {
    setActiveTab(tab);
    if (tab === "graph" && selectedKb && !graphData) {
      loadGraph(selectedKb.id);
    }
  };

  const handleCreateKb = async () => {
    if (!newKbName.trim()) return;
    setIsCreating(true);
    setError(null);
    const result = await createKnowledgeBase(newKbName, newKbDescription, newKbType, newKbIsGlobal);
    if (result.success) {
      setIsCreateOpen(false);
      setNewKbName("");
      setNewKbDescription("");
      setNewKbType("document");
      setNewKbIsGlobal(false);
      await loadKnowledgeBases();
    } else {
      setError(result.error ?? "Failed to create the knowledge base");
    }
    setIsCreating(false);
  };

  const handleDeleteKb = async (id: string) => {
    const result = await deleteKnowledgeBase(id);
    if (result.success) {
      setDeleteConfirm(null);
      if (selectedKb?.id === id) {
        pollAbortRef.current?.abort();
        pollAbortRef.current = null;
        setSelectedKb(null);
        setDocuments([]);
      }
      await loadKnowledgeBases();
    } else {
      setError(result.error ?? "Failed to delete the knowledge base");
    }
  };

  /** Insert or replace one document in the table, keyed by id. */
  const mergeDocument = useCallback((doc: Document) => {
    setDocuments((prev) => {
      const index = prev.findIndex((existing) => existing.id === doc.id);
      if (index === -1) return [doc, ...prev];
      const next = [...prev];
      next[index] = { ...next[index], ...doc };
      return next;
    });
  }, []);

  /**
   * Follow a freshly uploaded document until ingestion settles.
   *
   * Both upload endpoints return as soon as the file is stored, with
   * `status: "processing"`; parsing, embedding and graph extraction run in the
   * background. Without this poll the row stayed "processing" until the user
   * reloaded the page, and a failed ingestion was indistinguishable from a slow
   * one.
   */
  const trackIngestion = useCallback(
    async (doc: Document) => {
      mergeDocument(doc);
      const signal = pollAbortRef.current?.signal;
      const result = await waitForDocument(doc.id, { signal, onUpdate: mergeDocument });
      if (signal?.aborted) return;
      if (!result.success) {
        setError(result.error ?? "Failed to read the ingestion status");
        return;
      }
      // Chunk and document counts on the knowledge base only settle at the end.
      await loadKnowledgeBases();
    },
    [mergeDocument, loadKnowledgeBases]
  );

  const handleUpload = useCallback(
    async (files: File[]) => {
      if (!selectedKb) return;
      setIsUploading(true);
      setUploadProgress(0);
      setError(null);

      const failures: string[] = [];
      const uploaded: Document[] = [];
      for (let i = 0; i < files.length; i++) {
        const result = await uploadDocument(selectedKb.id, files[i], (progress) => {
          const totalProgress = Math.round(
            ((i * 100 + progress) / (files.length * 100)) * 100
          );
          setUploadProgress(totalProgress);
        });
        if (result.success && result.data) {
          uploaded.push(result.data);
        } else {
          failures.push(`${files[i].name}: ${result.error ?? "upload failed"}`);
        }
      }

      setIsUploading(false);
      setUploadProgress(100);
      if (failures.length > 0) setError(failures.join("\n"));
      if (uploaded.length > 0) setIsUploadOpen(false);

      await loadDocuments(selectedKb.id);
      // Deliberately not awaited: each upload keeps polling in the background
      // while the user carries on.
      uploaded.forEach((doc) => void trackIngestion(doc));
    },
    [selectedKb, loadDocuments, trackIngestion]
  );

  const handleImportProject = useCallback(
    async (files: File[]) => {
      if (!selectedKb || files.length === 0) return;
      setImportingProject(true);
      setImportProgress(0);
      setError(null);

      // `undefined` extensions: let the backend apply its own default set.
      const result = await importProject(
        selectedKb.id,
        files[0],
        undefined,
        (progress) => setImportProgress(progress)
      );

      setImportingProject(false);
      if (result.success && result.data) {
        setIsUploadOpen(false);
        await loadDocuments(selectedKb.id);
        void trackIngestion(result.data);
      } else {
        setError(result.error ?? "Failed to import the project");
      }
    },
    [selectedKb, loadDocuments, trackIngestion]
  );

  const handleDeleteDocument = async (docId: string) => {
    if (!selectedKb) return;
    const result = await deleteDocument(docId);
    if (result.success) {
      await loadDocuments(selectedKb.id);
      await loadKnowledgeBases();
    } else {
      setError(result.error ?? "Failed to delete the document");
    }
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  };

  const formatFileSize = (bytes?: number) => {
    if (!bytes) return "";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  };

  // KB Detail view
  if (selectedKb) {
    const kbType = (selectedKb.kbType || "document") as KbType;
    return (
      <div className="h-full flex flex-col">
        {/* Header */}
        <div className="border-b border-border px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Button variant="ghost" size="icon" onClick={handleBack}>
                <ArrowLeft className="h-4 w-4" />
              </Button>
              <div className="flex items-center gap-3">
                {getKbIcon(kbType)}
                <div>
                  <div className="flex items-center gap-2">
                    <h1 className="text-lg font-semibold">{selectedKb.name}</h1>
                    <Badge variant="secondary" className="text-[10px] uppercase tracking-wider">
                      {kbType}
                    </Badge>
                  </div>
                  {selectedKb.description && (
                    <p className="text-sm text-muted-foreground mt-0.5">
                      {selectedKb.description}
                    </p>
                  )}
                </div>
              </div>
            </div>
            <Button onClick={() => setIsUploadOpen(true)}>
              <Upload className="h-4 w-4 mr-2" />
              {kbType === "project" ? "Import Project" : "Upload"}
            </Button>
          </div>

          {/* Tabs */}
          <div className="flex gap-1 mt-4 -mb-[17px]">
            {(
              [
                { id: "documents", label: "Documents", icon: FileText },
                { id: "content", label: "Content", icon: Eye },
                { id: "graph", label: "Graph", icon: Network },
              ] as const
            ).map((tab) => (
              <button
                key={tab.id}
                onClick={() => handleTabChange(tab.id)}
                className={cn(
                  "flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium rounded-t-md transition-colors border-b-2",
                  activeTab === tab.id
                    ? "border-primary text-primary bg-primary/5"
                    : "border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                <tab.icon className="h-3.5 w-3.5" />
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {/* Tab content */}
        <div className="flex-1 overflow-y-auto p-6">
          <ErrorBanner message={error} onDismiss={() => setError(null)} />

          {/* Documents tab */}
          {activeTab === "documents" && (
            <>
              {documents.length === 0 ? (
                <div className="text-center py-12">
                  <FileText className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
                  <h3 className="text-lg font-medium mb-1">No documents yet</h3>
                  <p className="text-sm text-muted-foreground mb-4">
                    {kbType === "project"
                      ? "Import a ZIP file containing your project."
                      : kbType === "book"
                      ? "Upload a PDF or long document to get started."
                      : "Upload documents to build your knowledge base."}
                  </p>
                  <Button onClick={() => setIsUploadOpen(true)}>
                    <Plus className="h-4 w-4 mr-2" />
                    {kbType === "project" ? "Import Project" : "Upload Documents"}
                  </Button>
                </div>
              ) : (
                <div className="space-y-2">
                  <div className="text-sm text-muted-foreground mb-4">
                    {documents.length} document{documents.length !== 1 ? "s" : ""}
                  </div>
                  {documents.map((doc) => (
                    <div
                      key={doc.id}
                      className="flex items-center gap-4 p-4 rounded-lg border border-border hover:bg-muted/20 transition-colors group"
                    >
                      <div className="w-10 h-10 rounded-lg bg-muted/50 flex items-center justify-center flex-shrink-0">
                        <FileText className="h-5 w-5 text-muted-foreground" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium truncate">
                          {doc.filename}
                        </p>
                        <div className="flex items-center gap-3 mt-1">
                          {doc.fileSize && (
                            <span className="text-xs text-muted-foreground">
                              {formatFileSize(doc.fileSize)}
                            </span>
                          )}
                          <span className="text-xs text-muted-foreground">
                            {doc.chunkCount} chunks
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {formatDate(doc.createdAt)}
                          </span>
                        </div>
                        {/* The reason ingestion failed is only in `error`; without
                            it a failed document was a red badge and no clue. */}
                        {doc.status === "failed" && doc.error && (
                          <p className="text-xs text-destructive mt-1 break-words">
                            {doc.error}
                          </p>
                        )}
                      </div>
                      {doc.status && (
                        <Badge
                          variant={
                            doc.status === "completed"
                              ? "success"
                              : doc.status === "processing"
                              ? "warning"
                              : "destructive"
                          }
                          className="flex items-center gap-1"
                        >
                          {doc.status === "processing" && (
                            <Loader2 className="h-3 w-3 animate-spin" />
                          )}
                          {doc.status === "processing" && doc.stage
                            ? STAGE_LABELS[doc.stage]
                            : doc.status}
                        </Badge>
                      )}
                      <Button
                        variant="ghost"
                        size="icon"
                        className="text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity"
                        onClick={() => handleDeleteDocument(doc.id)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          {/* Content tab */}
          {activeTab === "content" && (
            <ChunkViewer knowledgeBaseId={selectedKb.id} />
          )}

          {/* Graph tab */}
          {activeTab === "graph" && (
            <div>
              {graphLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                </div>
              ) : graphData &&
                graphData.nodes.length > 0 ? (
                <div className="space-y-4">
                  <div className="flex items-center gap-3 text-sm text-muted-foreground">
                    <span>{graphData.nodes.length} nodes</span>
                    <span>&middot;</span>
                    <span>{graphData.edges.length} edges</span>
                  </div>
                  <div className="rounded-lg border border-border bg-muted/10 p-6 min-h-[400px] flex items-center justify-center">
                    <div className="text-center">
                      <Network className="h-12 w-12 text-muted-foreground mx-auto mb-3" />
                      <p className="text-sm text-muted-foreground">
                        Knowledge graph visualization
                      </p>
                      <p className="text-xs text-muted-foreground mt-1">
                        {graphData.nodes.length} entities and {graphData.edges.length} relationships
                      </p>
                    </div>
                  </div>
                  {/* Node/edge list fallback */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div className="space-y-2">
                      <h4 className="text-sm font-medium">Entities</h4>
                      <div className="max-h-[300px] overflow-y-auto space-y-1">
                        {graphData.nodes.slice(0, 50).map((node) => (
                          <div
                            key={node.id}
                            className="flex items-center gap-2 px-3 py-1.5 rounded bg-muted/30 text-sm"
                          >
                            <div className="w-2 h-2 rounded-full bg-primary" />
                            <span className="truncate">{node.label}</span>
                            {node.type && (
                              <Badge variant="secondary" className="text-[10px] ml-auto">
                                {node.type}
                              </Badge>
                            )}
                          </div>
                        ))}
                        {graphData.nodes.length > 50 && (
                          <p className="text-xs text-muted-foreground px-3 py-1">
                            +{graphData.nodes.length - 50} more
                          </p>
                        )}
                      </div>
                    </div>
                    <div className="space-y-2">
                      <h4 className="text-sm font-medium">Relationships</h4>
                      <div className="max-h-[300px] overflow-y-auto space-y-1">
                        {graphData.edges.slice(0, 50).map((edge, i) => (
                          <div
                            key={i}
                            className="flex items-center gap-2 px-3 py-1.5 rounded bg-muted/30 text-xs"
                          >
                            <span className="truncate flex-1">{edge.source}</span>
                            <Badge variant="outline" className="text-[10px] flex-shrink-0">
                              {edge.relation || edge.label}
                            </Badge>
                            <span className="truncate flex-1 text-right">{edge.target}</span>
                          </div>
                        ))}
                        {graphData.edges.length > 50 && (
                          <p className="text-xs text-muted-foreground px-3 py-1">
                            +{graphData.edges.length - 50} more
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="text-center py-12">
                  <Network className="h-12 w-12 text-muted-foreground mx-auto mb-3" />
                  <p className="text-sm text-muted-foreground">
                    No graph data available for this knowledge base
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Upload dialog */}
        <Dialog open={isUploadOpen} onOpenChange={setIsUploadOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>
                {kbType === "project" ? "Import Project" : "Upload Documents"}
              </DialogTitle>
              <DialogDescription>
                {kbType === "project"
                  ? `Upload a ZIP file containing your project to "${selectedKb.name}".`
                  : kbType === "book"
                  ? `Upload a PDF or long document to "${selectedKb.name}".`
                  : `Upload files to "${selectedKb.name}". Supported formats include PDF, DOCX, Markdown, plain text, CSV, Excel, and code files.`}
              </DialogDescription>
            </DialogHeader>
            {kbType === "project" ? (
              <div className="space-y-4">
                <div
                  className="border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors hover:border-primary/50 hover:bg-muted/50"
                  onClick={() => {
                    const input = document.createElement("input");
                    input.type = "file";
                    input.accept = ".zip";
                    input.onchange = (e) => {
                      const files = (e.target as HTMLInputElement).files;
                      if (files && files.length > 0) {
                        handleImportProject(Array.from(files));
                      }
                    };
                    input.click();
                  }}
                >
                  <FolderArchive className="h-10 w-10 mx-auto mb-3 text-emerald-500" />
                  <p className="text-sm font-medium">
                    Click to select a ZIP file
                  </p>
                  <p className="text-xs text-muted-foreground mt-1">
                    Your project will be extracted and indexed for code analysis
                  </p>
                </div>
                {importingProject && (
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span>Importing project...</span>
                      <span>{importProgress}%</span>
                    </div>
                    <div className="h-2 bg-muted rounded-full overflow-hidden">
                      <div
                        className="h-full bg-emerald-500 transition-all duration-300 rounded-full"
                        style={{ width: `${importProgress}%` }}
                      />
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <FileUpload
                onUpload={handleUpload}
                isUploading={isUploading}
                progress={uploadProgress}
              />
            )}
          </DialogContent>
        </Dialog>
      </div>
    );
  }

  // Knowledge base list view
  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="border-b border-border px-6 py-4 flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold">Knowledge Bases</h1>
          <p className="text-sm text-muted-foreground">
            Manage your document collections for RAG-powered search and chat.
          </p>
        </div>
        <Button onClick={() => setIsCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          Create New
        </Button>
      </div>

      {/* Knowledge base grid */}
      <div className="flex-1 overflow-y-auto p-6">
        <ErrorBanner message={error} onDismiss={() => setError(null)} />

        {isLoading ? (
          <div className="flex items-center justify-center h-48">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          </div>
        ) : knowledgeBases.length === 0 ? (
          <div className="text-center py-12">
            <Database className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
            <h3 className="text-lg font-medium mb-1">
              No knowledge bases yet
            </h3>
            <p className="text-sm text-muted-foreground mb-4">
              Create your first knowledge base to start uploading documents.
            </p>
            <Button onClick={() => setIsCreateOpen(true)}>
              <Plus className="h-4 w-4 mr-2" />
              Create Knowledge Base
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {knowledgeBases.map((kb) => {
              const kbType = (kb.kbType || "document") as KbType;
              const config = KB_TYPE_CONFIG[kbType];
              const canDelete = !kb.isGlobal || isAdmin;
              return (
                <Card
                  key={kb.id}
                  className={cn(
                    "cursor-pointer hover:border-primary/50 hover:shadow-md transition-all group",
                    kb.isGlobal && "border-violet-500/30"
                  )}
                  onClick={() => handleSelectKb(kb)}
                >
                  <CardHeader className="pb-3">
                    <div className="flex items-start justify-between">
                      <div
                        className={cn(
                          "w-10 h-10 rounded-lg flex items-center justify-center",
                          kbType === "document" && "bg-blue-500/10",
                          kbType === "book" && "bg-amber-500/10",
                          kbType === "project" && "bg-emerald-500/10"
                        )}
                      >
                        <span className={config.color}>{config.icon}</span>
                      </div>
                      <div className="flex items-center gap-1">
                        {kb.isGlobal ? (
                          <Badge variant="secondary" className="text-[10px] gap-1 bg-violet-500/10 text-violet-600 dark:text-violet-400 border-violet-500/20">
                            <Globe className="h-3 w-3" />
                            全局
                          </Badge>
                        ) : (
                          <Badge variant="secondary" className="text-[10px] gap-1">
                            <Lock className="h-3 w-3" />
                            私有
                          </Badge>
                        )}
                        {canDelete && (
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-8 w-8 text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity"
                            onClick={(e) => {
                              e.stopPropagation();
                              setDeleteConfirm(kb.id);
                            }}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </div>
                    <CardTitle className="text-base mt-3">{kb.name}</CardTitle>
                    <CardDescription className="line-clamp-2">
                      {kb.description || "No description"}
                    </CardDescription>
                  </CardHeader>
                  <CardFooter className="text-xs text-muted-foreground gap-4">
                    <Badge
                      variant="secondary"
                      className="text-[10px] uppercase tracking-wider"
                    >
                      {kbType}
                    </Badge>
                    <span className="flex items-center gap-1">
                      <Hash className="h-3 w-3" />
                      {kb.documentCount} docs
                    </span>
                    <span className="flex items-center gap-1">
                      <Calendar className="h-3 w-3" />
                      {formatDate(kb.createdAt)}
                    </span>
                  </CardFooter>
                </Card>
              );
            })}

            {/* Create new card */}
            <Card
              className="cursor-pointer border-dashed hover:border-primary/50 hover:bg-muted/20 transition-all flex items-center justify-center min-h-[180px]"
              onClick={() => setIsCreateOpen(true)}
            >
              <div className="text-center p-6">
                <div className="w-10 h-10 rounded-lg bg-muted/50 flex items-center justify-center mx-auto mb-3">
                  <Plus className="h-5 w-5 text-muted-foreground" />
                </div>
                <p className="text-sm font-medium text-muted-foreground">
                  Create New
                </p>
              </div>
            </Card>
          </div>
        )}
      </div>

      {/* Create KB dialog */}
      <Dialog open={isCreateOpen} onOpenChange={setIsCreateOpen}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>Create Knowledge Base</DialogTitle>
            <DialogDescription>
              Create a new knowledge base to organize and query your documents.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-5 py-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">Name</label>
              <Input
                placeholder="e.g., Product Documentation"
                value={newKbName}
                onChange={(e) => setNewKbName(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">Description</label>
              <Input
                placeholder="Brief description of this knowledge base"
                value={newKbDescription}
                onChange={(e) => setNewKbDescription(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">Type</label>
              <div className="grid grid-cols-3 gap-3">
                {(Object.entries(KB_TYPE_CONFIG) as [KbType, typeof KB_TYPE_CONFIG["document"]][]).map(
                  ([type, config]) => (
                    <button
                      key={type}
                      onClick={() => setNewKbType(type)}
                      className={cn(
                        "flex flex-col items-center gap-2 p-4 rounded-lg border-2 transition-all text-center",
                        newKbType === type
                          ? "border-primary bg-primary/5"
                          : "border-border hover:border-primary/30 hover:bg-muted/30"
                      )}
                    >
                      <span className={config.color}>{config.icon}</span>
                      <span className="text-xs font-medium">{config.label}</span>
                      <span className="text-[10px] text-muted-foreground leading-tight">
                        {config.description}
                      </span>
                    </button>
                  )
                )}
              </div>
            </div>
            {/* Global toggle - only visible to admin */}
            {isAdmin && (
              <div className="flex items-center justify-between rounded-lg border border-border p-4">
                <div className="space-y-0.5">
                  <label className="text-sm font-medium flex items-center gap-2">
                    <Globe className="h-4 w-4 text-violet-500" />
                    设为全局知识库
                  </label>
                  <p className="text-xs text-muted-foreground">
                    全局知识库对所有用户可见
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setNewKbIsGlobal(!newKbIsGlobal)}
                  className={cn(
                    "relative inline-flex h-6 w-11 items-center rounded-full transition-colors",
                    newKbIsGlobal ? "bg-violet-500" : "bg-muted"
                  )}
                >
                  <span
                    className={cn(
                      "inline-block h-4 w-4 transform rounded-full bg-white transition-transform",
                      newKbIsGlobal ? "translate-x-6" : "translate-x-1"
                    )}
                  />
                </button>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleCreateKb}
              disabled={!newKbName.trim() || isCreating}
            >
              {isCreating && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
              Create
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirmation dialog */}
      <Dialog
        open={!!deleteConfirm}
        onOpenChange={() => setDeleteConfirm(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Knowledge Base</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete this knowledge base? This action
              cannot be undone. All documents and embeddings will be permanently
              removed.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteConfirm(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteConfirm && handleDeleteKb(deleteConfirm)}
            >
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/**
 * Dismissible error strip.
 *
 * Every failed call on this page used to be swallowed silently — `if
 * (result.success)` with no else — so a rejected upload or a permission error
 * looked exactly like nothing happening.
 */
function ErrorBanner({
  message,
  onDismiss,
}: {
  message: string | null;
  onDismiss: () => void;
}) {
  if (!message) return null;
  return (
    <div className="mb-4 flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
      <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
      <span className="flex-1 whitespace-pre-wrap break-words">{message}</span>
      <button
        type="button"
        onClick={onDismiss}
        className="flex-shrink-0 opacity-70 hover:opacity-100"
        aria-label="Dismiss error"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}
