// Shapes here mirror docs/CONTRACT.md section 5. Both backends serialise
// camelCase, so these interfaces are valid against either one.

/**
 * `knowledgeBaseId` value meaning "search every private KB I own" (contract
 * section 5). Real KB ids are 32-char uuid4 hex, so this never collides.
 */
export const ALL_KB_SENTINEL = "__all__";

export interface User {
  username: string;
  role: "admin" | "user";
  token: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  username: string;
  role: string;
}

export type KbType = "document" | "book" | "project";

export interface KnowledgeBase {
  id: string;
  name: string;
  description: string;
  kbType: KbType;
  documentCount: number;
  createdAt: string;
  updatedAt?: string;
  owner?: string;
  isGlobal?: boolean;
  /** Vector width the collection was created with; fixed for the KB's lifetime. */
  embeddingDim?: number;
}

export type DocumentStatus = "processing" | "completed" | "failed";

export type DocumentStage = "parsing" | "embedding" | "graph";

export interface Document {
  id: string;
  knowledgeBaseId: string;
  filename: string;
  fileType: string;
  fileSize?: number;
  /** Uploads return `processing`; poll `getDocument` until it settles. */
  status?: DocumentStatus;
  /** Step within ingestion; only meaningful while `status === "processing"`. */
  stage?: DocumentStage;
  /** Reason the ingest failed; only set when `status === "failed"`. */
  error?: string;
  chunkCount: number;
  createdAt: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
  timestamp: string;
  confidence?: number;
  route?: string;
  metadata?: ChatMetadata;
  stages?: StageEvent[];
}

/** Payload of the `stage` SSE event (contract section 4). */
export interface StageEvent {
  stage: "analyzing" | "retrieving" | "graph" | "generating";
  status: "start" | "done";
  /** Only set on `status === "done"`. */
  elapsedMs?: number;
}

/** Payload of the `metadata` SSE event (contract section 4). */
export interface ChatMetadata {
  route?: string;
  confidence?: number;
  reason?: string;
  intent?: string;
  complexity?: string;
}

export interface Source {
  text: string;
  score: number;
  source: string;
  documentId: string;
  /** Reranker score; absent when reranking was skipped. */
  rerankScore?: number | null;
  metadata?: Record<string, unknown>;
}

export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface GraphNode {
  id: string;
  label: string;
  type?: string;
  properties?: Record<string, unknown>;
}

export interface GraphEdge {
  id?: string;
  source: string;
  target: string;
  relation: string;
  label?: string;
  weight?: number;
}

/**
 * Server settings, exactly the read shape from contract section 5. Secrets are
 * never returned, which is why they are not in this interface — they live in
 * {@link SettingsUpdate} instead.
 */
export interface Settings {
  llmProvider: string;
  openaiApiBase: string;
  openaiModel: string;
  ollamaBaseUrl: string;
  ollamaModel: string;
  volcengineApiBase: string;
  volcengineModel: string;
  embeddingProvider: string;
  openaiEmbeddingModel: string;
  localEmbeddingModel: string;
  rerankerProvider: string;
  milvusHost: string;
  milvusPort: number;
  neo4jUri: string;
  chunkSize: number;
  chunkOverlap: number;
}

/** PUT body: every field optional, plus the write-only secrets. */
export type SettingsUpdate = Partial<Settings> & {
  openaiApiKey?: string;
  volcengineApiKey?: string;
  neo4jUser?: string;
  neo4jPassword?: string;
};

/** Which backend the browser talks to. Client-side only — never sent anywhere. */
export type BackendChoice = "spring" | "langchain";

export interface LocalPreferences {
  backend: BackendChoice;
}

export interface ChatRequest {
  question: string;
  knowledgeBaseId: string;
  sessionId?: string;
}

export interface Session {
  sessionId: string;
  title: string;
  knowledgeBaseId: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChunkItem {
  id: string;
  text: string;
  metadata: Record<string, unknown>;
  documentId: string;
  source: string;
  chunkIndex?: number;
}

export interface ChunkPage {
  chunks: ChunkItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

export interface FeedbackRequest {
  sessionId: string;
  messageIndex: number;
  rating: "up" | "down";
  comment?: string;
}

export interface FeedbackStats {
  total: number;
  positive: number;
  negative: number;
  satisfactionRate: number;
}
