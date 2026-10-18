import type {
  KnowledgeBase,
  Document,
  ChatMessage,
  ChatMetadata,
  StageEvent,
  LlmCallRecord,
  GraphData,
  Settings,
  SettingsUpdate,
  ChatRequest,
  ApiResponse,
  AuthResponse,
  BackendChoice,
  KbType,
  Session,
  ChunkPage,
  FeedbackRequest,
  FeedbackStats,
} from "@/types";

export const PREFERENCES_KEY = "rag-preferences";
export const USER_KEY = "rag-user";

/**
 * Backend base URLs.
 *
 * These used to be hardcoded to localhost, so any deployment that was not the
 * developer's own machine talked to the browser's own localhost and failed.
 * They now come from build-time env vars (`NEXT_PUBLIC_*` is inlined by Next),
 * with the dev defaults kept as a fallback.
 */
const BASE_URLS: Record<BackendChoice, string> = {
  spring: process.env.NEXT_PUBLIC_SPRING_API_URL || "http://localhost:8080",
  langchain: process.env.NEXT_PUBLIC_LANGCHAIN_API_URL || "http://localhost:8000",
};

const DEFAULT_BACKEND: BackendChoice =
  (process.env.NEXT_PUBLIC_DEFAULT_BACKEND as BackendChoice) || "spring";

export function getBackend(): BackendChoice {
  if (typeof window === "undefined") return DEFAULT_BACKEND;
  try {
    const raw = localStorage.getItem(PREFERENCES_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed?.backend === "langchain" || parsed?.backend === "spring") {
        return parsed.backend;
      }
    }
  } catch {
    /* corrupt preferences fall back to the default */
  }
  return DEFAULT_BACKEND;
}

export function setBackend(backend: BackendChoice) {
  if (typeof window === "undefined") return;
  localStorage.setItem(PREFERENCES_KEY, JSON.stringify({ backend }));
}

export function getBaseUrl(backend: BackendChoice = getBackend()): string {
  return BASE_URLS[backend].replace(/\/$/, "");
}

function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const stored = localStorage.getItem(USER_KEY);
    if (!stored) return null;
    const user = JSON.parse(stored);
    return typeof user?.token === "string" ? user.token : null;
  } catch {
    return null;
  }
}

function getAuthHeader(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function handleUnauthorized() {
  if (typeof window === "undefined") return;
  localStorage.removeItem(USER_KEY);
  // Avoid bouncing an already-visible login page.
  if (!window.location.pathname.startsWith("/login")) {
    window.location.href = "/login";
  }
}

/**
 * Pull a human-readable message out of an error response.
 *
 * Both backends answer with `{"detail": "..."}` (FastAPI's shape, which the
 * Spring exception handler copies). The old client surfaced the raw JSON, so the
 * UI showed users things like `{"detail":"Knowledge base not found"}`.
 */
async function errorMessage(response: Response): Promise<string> {
  const body = await response.text();
  if (body) {
    try {
      const parsed = JSON.parse(body);
      const detail = parsed?.detail ?? parsed?.message ?? parsed?.error;
      if (typeof detail === "string" && detail) return detail;
    } catch {
      return body;
    }
  }
  return `HTTP ${response.status}: ${response.statusText}`;
}

async function request<T>(
  endpoint: string,
  options?: RequestInit,
  // The login/register endpoints answer 401 for wrong credentials; treating that
  // as an expired session would clear storage and swallow the real message.
  treat401AsExpiredSession: boolean = true
): Promise<ApiResponse<T>> {
  try {
    const response = await fetch(`${getBaseUrl()}${endpoint}`, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...getAuthHeader(),
        ...options?.headers,
      },
    });

    if (response.status === 401 && treat401AsExpiredSession) {
      handleUnauthorized();
      return { success: false, error: "Session expired, please sign in again" };
    }
    if (!response.ok) {
      return { success: false, error: await errorMessage(response) };
    }

    // 204 and empty bodies are valid successes.
    const text = await response.text();
    if (!text) return { success: true, data: undefined };
    return { success: true, data: JSON.parse(text) as T };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : "Network error",
    };
  }
}

/** Shared multipart upload with progress, used for both file and project import. */
function upload<T>(
  endpoint: string,
  file: File,
  extraFields: Record<string, string> = {},
  onProgress?: (progress: number) => void
): Promise<ApiResponse<T>> {
  const formData = new FormData();
  formData.append("file", file);
  for (const [key, value] of Object.entries(extraFields)) {
    formData.append(key, value);
  }

  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${getBaseUrl()}${endpoint}`);

    const token = getToken();
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);

    xhr.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status === 401) {
        handleUnauthorized();
        resolve({ success: false, error: "Session expired, please sign in again" });
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve({ success: true, data: JSON.parse(xhr.responseText) as T });
        } catch {
          resolve({ success: true, data: undefined });
        }
        return;
      }
      // Same {"detail": ...} shape as the JSON path above.
      let message = `HTTP ${xhr.status}: ${xhr.statusText}`;
      try {
        const detail = JSON.parse(xhr.responseText)?.detail;
        if (typeof detail === "string" && detail) message = detail;
      } catch {
        if (xhr.responseText) message = xhr.responseText;
      }
      resolve({ success: false, error: message });
    });

    xhr.addEventListener("error", () => {
      resolve({ success: false, error: "Network error during upload" });
    });

    xhr.send(formData);
  });
}

// --- Knowledge bases -------------------------------------------------------

export function listKnowledgeBases(): Promise<ApiResponse<KnowledgeBase[]>> {
  return request<KnowledgeBase[]>("/api/knowledge-bases");
}

export function getKnowledgeBase(id: string): Promise<ApiResponse<KnowledgeBase>> {
  return request<KnowledgeBase>(`/api/knowledge-bases/${encodeURIComponent(id)}`);
}

export function createKnowledgeBase(
  name: string,
  description: string,
  kbType: KbType = "document",
  isGlobal: boolean = false
): Promise<ApiResponse<KnowledgeBase>> {
  return request<KnowledgeBase>("/api/knowledge-bases", {
    method: "POST",
    body: JSON.stringify({ name, description, kbType, isGlobal }),
  });
}

export function deleteKnowledgeBase(id: string): Promise<ApiResponse<{ message: string }>> {
  return request<{ message: string }>(`/api/knowledge-bases/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

export function listChunks(
  kbId: string,
  page: number = 1,
  pageSize: number = 20,
  search?: string
): Promise<ApiResponse<ChunkPage>> {
  const params = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  });
  if (search) params.set("search", search);
  return request<ChunkPage>(
    `/api/knowledge-bases/${encodeURIComponent(kbId)}/chunks?${params.toString()}`
  );
}

// --- Documents -------------------------------------------------------------

export function listDocuments(
  knowledgeBaseId: string
): Promise<ApiResponse<Document[]>> {
  return request<Document[]>(
    `/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`
  );
}

/** One document, including `status` and `error`. Poll this after an upload. */
export function getDocument(documentId: string): Promise<ApiResponse<Document>> {
  return request<Document>(`/api/documents/${encodeURIComponent(documentId)}`);
}

/**
 * Upload a file. Returns as soon as the server has stored it, with
 * `status: "processing"` — ingestion continues in the background, so callers
 * must poll {@link getDocument} (or {@link waitForDocument}) to see it finish.
 */
export function uploadDocument(
  knowledgeBaseId: string,
  file: File,
  onProgress?: (progress: number) => void
): Promise<ApiResponse<Document>> {
  return upload<Document>(
    `/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`,
    file,
    {},
    onProgress
  );
}

export function importProject(
  kbId: string,
  file: File,
  fileExtensions?: string,
  onProgress?: (progress: number) => void
): Promise<ApiResponse<Document>> {
  return upload<Document>(
    `/api/knowledge-bases/${encodeURIComponent(kbId)}/import-project`,
    file,
    fileExtensions ? { fileExtensions } : {},
    onProgress
  );
}

export function deleteDocument(
  documentId: string
): Promise<ApiResponse<{ message: string }>> {
  return request<{ message: string }>(`/api/documents/${encodeURIComponent(documentId)}`, {
    method: "DELETE",
  });
}

/**
 * Poll a document until ingestion settles.
 *
 * Resolves with the final document on `completed`/`failed`, or with whatever the
 * last poll returned once `timeoutMs` elapses (a big project ZIP can legitimately
 * take longer than any sensible timeout, so this is not treated as an error).
 */
export async function waitForDocument(
  documentId: string,
  options: {
    intervalMs?: number;
    timeoutMs?: number;
    onUpdate?: (doc: Document) => void;
    signal?: AbortSignal;
  } = {}
): Promise<ApiResponse<Document>> {
  const { intervalMs = 2000, timeoutMs = 10 * 60 * 1000, onUpdate, signal } = options;
  const deadline = Date.now() + timeoutMs;
  let last: ApiResponse<Document> = { success: false, error: "Not polled yet" };

  while (Date.now() < deadline && !signal?.aborted) {
    last = await getDocument(documentId);
    if (!last.success) return last;
    if (last.data) {
      onUpdate?.(last.data);
      if (last.data.status !== "processing") return last;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return last;
}

// --- Sessions --------------------------------------------------------------

export async function listSessions(
  knowledgeBaseId?: string
): Promise<ApiResponse<Session[]>> {
  const params = knowledgeBaseId
    ? `?knowledgeBaseId=${encodeURIComponent(knowledgeBaseId)}`
    : "";
  // The endpoint answers `{sessions: [...]}`, not a bare array (contract §5).
  const response = await request<{ sessions: Session[] }>(`/api/sessions${params}`);
  if (!response.success) return { success: false, error: response.error };
  return { success: true, data: response.data?.sessions ?? [] };
}

export function createSession(
  knowledgeBaseId: string,
  title?: string
): Promise<ApiResponse<Session>> {
  return request<Session>("/api/sessions", {
    method: "POST",
    body: JSON.stringify(title ? { knowledgeBaseId, title } : { knowledgeBaseId }),
  });
}

export function getSession(sessionId: string): Promise<ApiResponse<Session>> {
  return request<Session>(`/api/sessions/${encodeURIComponent(sessionId)}`);
}

export function deleteSession(
  sessionId: string
): Promise<ApiResponse<{ message: string }>> {
  return request<{ message: string }>(`/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
  });
}

export async function getChatHistory(
  sessionId: string
): Promise<ApiResponse<ChatMessage[]>> {
  // `{sessionId, messages}` per contract §5.
  const response = await request<{ sessionId: string; messages: ChatMessage[] }>(
    `/api/chat/history/${encodeURIComponent(sessionId)}`
  );
  if (!response.success) return { success: false, error: response.error };
  return { success: true, data: response.data?.messages ?? [] };
}

// --- Chat ------------------------------------------------------------------

export interface ChatStreamHandlers {
  /** The server-assigned session id, sent before anything else. */
  onSession?: (sessionId: string) => void;
  onMetadata?: (metadata: ChatMetadata) => void;
  onConfidence?: (confidence: number) => void;
  onStage?: (stage: StageEvent) => void;
  onChunk: (text: string) => void;
  onSources?: (sources: ChatMessage["sources"]) => void;
  onSuggestedQuestions?: (questions: string[]) => void;
  onLlmCall?: (call: LlmCallRecord) => void;
  onDone: (sources?: ChatMessage["sources"]) => void;
  onError: (error: string) => void;
}

/**
 * Stream a chat answer over SSE.
 *
 * The parser is frame-based: it splits on the blank line that terminates an SSE
 * event and joins that event's `data:` lines with `\n`. The previous version
 * handled each `data:` line in isolation, which silently dropped every newline in
 * the model's Markdown — both backends split multi-line payloads across several
 * `data:` lines, as the spec requires.
 */
export function chatStream(
  chatRequest: ChatRequest,
  handlers: ChatStreamHandlers
): AbortController {
  const controller = new AbortController();
  let sources: ChatMessage["sources"] | undefined;
  let finished = false;

  const finish = (error?: string) => {
    if (finished) return;
    finished = true;
    if (error) handlers.onError(error);
    else handlers.onDone(sources);
  };

  const dispatch = (event: string, data: string) => {
    switch (event) {
      case "session":
        handlers.onSession?.(data);
        break;
      case "metadata":
        try {
          handlers.onMetadata?.(JSON.parse(data) as ChatMetadata);
        } catch {
          /* a malformed metadata frame is not worth failing the answer over */
        }
        break;
      case "confidence": {
        const value = Number(data);
        if (!Number.isNaN(value)) handlers.onConfidence?.(value);
        break;
      }
      case "stage":
        try {
          handlers.onStage?.(JSON.parse(data) as StageEvent);
        } catch {
          /* a malformed stage frame is not worth failing the answer over */
        }
        break;
      case "chunk":
        handlers.onChunk(data);
        break;
      case "sources":
        try {
          const parsed = JSON.parse(data);
          if (Array.isArray(parsed)) {
            sources = parsed;
            handlers.onSources?.(sources);
          }
        } catch {
          /* ignore */
        }
        break;
      case "suggestedQuestions":
        try {
          const parsed = JSON.parse(data);
          if (Array.isArray(parsed)) handlers.onSuggestedQuestions?.(parsed);
        } catch {
          /* a malformed suggestedQuestions frame is not worth failing the answer over */
        }
        break;
      case "llmCall":
        try {
          handlers.onLlmCall?.(JSON.parse(data) as LlmCallRecord);
        } catch {
          /* a malformed llmCall frame is not worth failing the answer over */
        }
        break;
      case "done":
        finish();
        break;
      case "error":
        finish(data || "The server reported an error");
        break;
      default:
        // Unknown event names are ignored on purpose: adding one to the backends
        // should not break an older frontend.
        break;
    }
  };

  /** Parse one SSE frame: an `event:` name plus its `data:` lines. */
  const handleFrame = (frame: string) => {
    let event = "message";
    const dataLines: string[] = [];
    for (const rawLine of frame.split("\n")) {
      const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
      if (!line || line.startsWith(":")) continue; // comment / keep-alive
      const colon = line.indexOf(":");
      const field = colon === -1 ? line : line.slice(0, colon);
      // A single space after the colon is part of the framing, not the value.
      let value = colon === -1 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);

      if (field === "event") event = value;
      else if (field === "data") dataLines.push(value);
    }
    if (dataLines.length > 0 || event !== "message") {
      dispatch(event, dataLines.join("\n"));
    }
  };

  fetch(`${getBaseUrl()}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...getAuthHeader() },
    body: JSON.stringify(chatRequest),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (response.status === 401) {
        handleUnauthorized();
        finish("Session expired, please sign in again");
        return;
      }
      if (!response.ok) {
        finish(await errorMessage(response));
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) {
        finish("No response body");
        return;
      }

      const decoder = new TextDecoder();
      // Frames are separated by a blank line; \r\n\r\n is equally legal.
      const frameSeparator = /\r?\n\r?\n/;
      let buffer = "";

      while (!finished) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let match = frameSeparator.exec(buffer);
        while (match) {
          const frame = buffer.slice(0, match.index);
          buffer = buffer.slice(match.index + match[0].length);
          handleFrame(frame);
          if (finished) break;
          match = frameSeparator.exec(buffer);
        }
      }

      // A last frame with no trailing blank line still counts.
      if (buffer.trim()) handleFrame(buffer);
      finish();
    })
    .catch((error) => {
      if (error?.name === "AbortError") {
        finished = true;
        return;
      }
      finish(error instanceof Error ? error.message : "Network error");
    });

  return controller;
}

// --- Graph -----------------------------------------------------------------

export function getGraphData(
  knowledgeBaseId: string
): Promise<ApiResponse<GraphData>> {
  return request<GraphData>(`/api/graph/${encodeURIComponent(knowledgeBaseId)}`);
}

export function queryGraph(
  knowledgeBaseId: string,
  entity: string
): Promise<ApiResponse<GraphData>> {
  return request<GraphData>("/api/graph/query", {
    method: "POST",
    // camelCase: the old snake_case `knowledge_base_id` never bound, so every
    // query was rejected as a missing knowledge base id.
    body: JSON.stringify({ knowledgeBaseId, entity }),
  });
}

// --- Feedback --------------------------------------------------------------

export function submitFeedback(
  feedback: FeedbackRequest
): Promise<ApiResponse<{ success: boolean }>> {
  return request<{ success: boolean }>("/api/feedback", {
    method: "POST",
    body: JSON.stringify(feedback),
  });
}

export function getFeedbackStats(): Promise<ApiResponse<FeedbackStats>> {
  return request<FeedbackStats>("/api/feedback/stats");
}

// --- Settings --------------------------------------------------------------

export function getSettings(): Promise<ApiResponse<Settings>> {
  return request<Settings>("/api/settings");
}

/** Only the fields present are applied; secrets are write-only. */
export function updateSettings(
  settings: SettingsUpdate
): Promise<ApiResponse<Settings>> {
  return request<Settings>("/api/settings", {
    method: "PUT",
    body: JSON.stringify(settings),
  });
}

// --- Auth ------------------------------------------------------------------

export function loginApi(
  username: string,
  password: string
): Promise<ApiResponse<AuthResponse>> {
  return request<AuthResponse>(
    "/api/auth/login",
    { method: "POST", body: JSON.stringify({ username, password }) },
    false
  );
}

export function registerApi(
  username: string,
  password: string
): Promise<ApiResponse<AuthResponse>> {
  return request<AuthResponse>(
    "/api/auth/register",
    { method: "POST", body: JSON.stringify({ username, password }) },
    false
  );
}
