"use client";

import React, { useCallback, useEffect, useState } from "react";
import { Save, Loader2, CheckCircle, AlertCircle, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from "@/components/ui/card";
import {
  getBackend,
  getBaseUrl,
  getSettings,
  setBackend,
  updateSettings,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import type { BackendChoice, Settings, SettingsUpdate } from "@/types";

/**
 * Settings screen.
 *
 * Two independent things live here and the old page conflated them:
 *
 * 1. Which backend the browser talks to. Purely a client-side preference; it is
 *    never sent anywhere.
 * 2. The server's own settings, whose shape is fixed by docs/CONTRACT.md §5.
 *    The old page invented its own fields (`modelName`, `apiKey`, `apiBaseUrl`)
 *    that no backend has ever read or returned, so nothing here took effect.
 *
 * `llmProvider`, `embeddingProvider`, `rerankerProvider`, `chunkSize`,
 * `chunkOverlap`, and the OpenAI/Ollama base URL, model name and (OpenAI) key
 * apply immediately — the Spring backend rebuilds that provider's client in
 * place. Volcengine fields and the infrastructure (Milvus, Neo4j) section are
 * read once at startup, so they are shown but marked as needing a restart.
 */

const LLM_PROVIDERS = [
  { value: "openai", label: "OpenAI / OpenAI-compatible" },
  { value: "ollama", label: "Ollama (local)" },
  { value: "volcengine", label: "Volcengine (Python backend only)" },
];

const EMBEDDING_PROVIDERS = [
  { value: "openai", label: "OpenAI" },
  { value: "local", label: "Local sentence-transformers (Python backend only)" },
];

const RERANKER_PROVIDERS = [
  { value: "llm", label: "LLM-scored" },
  { value: "local", label: "Local cross-encoder (Python backend only)" },
  { value: "none", label: "None (keep vector order)" },
];

/** Secrets are write-only: the server never returns them, so they start blank. */
interface SecretFields {
  openaiApiKey: string;
  volcengineApiKey: string;
  neo4jUser: string;
  neo4jPassword: string;
}

const EMPTY_SECRETS: SecretFields = {
  openaiApiKey: "",
  volcengineApiKey: "",
  neo4jUser: "",
  neo4jPassword: "",
};

export default function SettingsPage() {
  const { isAdmin } = useAuth();
  const [backend, setBackendState] = useState<BackendChoice>("spring");
  const [settings, setSettings] = useState<Settings | null>(null);
  const [secrets, setSecrets] = useState<SecretFields>(EMPTY_SECRETS);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [status, setStatus] = useState<"idle" | "success" | "error">("idle");
  const [message, setMessage] = useState("");

  const loadSettings = useCallback(async () => {
    setIsLoading(true);
    setStatus("idle");
    const result = await getSettings();
    if (result.success && result.data) {
      setSettings(result.data);
      setSecrets(EMPTY_SECRETS);
    } else {
      setStatus("error");
      setMessage(result.error || "Failed to load settings");
    }
    setIsLoading(false);
  }, []);

  useEffect(() => {
    setBackendState(getBackend());
  }, []);

  // Reload whenever the backend changes: the two services have independent
  // settings even though they share the metadata database.
  useEffect(() => {
    loadSettings();
  }, [backend, loadSettings]);

  const handleBackendChange = (choice: BackendChoice) => {
    setBackend(choice);
    setBackendState(choice);
  };

  const updateField = <K extends keyof Settings>(field: K, value: Settings[K]) => {
    setSettings((prev) => (prev ? { ...prev, [field]: value } : prev));
  };

  const handleSave = async () => {
    if (!settings) return;
    setIsSaving(true);
    setStatus("idle");
    setMessage("");

    // Only send the secrets the user actually typed; an empty string would
    // otherwise overwrite a working key with nothing.
    const payload: SettingsUpdate = { ...settings };
    for (const [key, value] of Object.entries(secrets)) {
      if (value) payload[key as keyof SecretFields] = value;
    }

    const result = await updateSettings(payload);
    if (result.success && result.data) {
      setSettings(result.data);
      setSecrets(EMPTY_SECRETS);
      setStatus("success");
      setMessage("Settings saved");
      setTimeout(() => setStatus("idle"), 3000);
    } else {
      setStatus("error");
      setMessage(result.error || "Failed to save settings");
    }
    setIsSaving(false);
  };

  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-2xl mx-auto p-6 space-y-6">
        <div>
          <h1 className="text-2xl font-semibold">Settings</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Pick a backend, and configure the providers it uses.
          </p>
        </div>

        {/* Backend selection — local preference, always editable */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Backend Service</CardTitle>
            <CardDescription>
              Both backends read the same knowledge bases, so you can switch freely
              and compare their answers.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <label className="text-sm font-medium" htmlFor="backend">
              Backend Framework
            </label>
            <Select
              id="backend"
              options={[
                { value: "spring", label: "Spring AI" },
                { value: "langchain", label: "LangChain / FastAPI" },
              ]}
              value={backend}
              onChange={(e) => handleBackendChange(e.target.value as BackendChoice)}
            />
            <p className="text-xs text-muted-foreground">
              Requests go to <code>{getBaseUrl(backend)}</code>
            </p>
          </CardContent>
        </Card>

        {isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground py-6">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading settings from the backend…
          </div>
        ) : !settings ? (
          <div className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
            <div className="flex-1">
              <p>{message || "Settings are unavailable."}</p>
              <button
                type="button"
                className="underline mt-1"
                onClick={loadSettings}
              >
                Retry
              </button>
            </div>
          </div>
        ) : (
          <>
            {!isAdmin && (
              <div className="flex items-start gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
                <Info className="h-4 w-4 flex-shrink-0 mt-0.5" />
                <span>
                  Only administrators can change these. The values below are
                  read-only for your account.
                </span>
              </div>
            )}

            {/* LLM */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">LLM Provider</CardTitle>
                <CardDescription>
                  Used for query analysis, reranking, graph extraction and answers.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <Field label="Provider" htmlFor="llmProvider" applies="immediately">
                  <Select
                    id="llmProvider"
                    options={LLM_PROVIDERS}
                    value={settings.llmProvider}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("llmProvider", e.target.value)}
                  />
                </Field>

                <Field label="OpenAI base URL" htmlFor="openaiApiBase" applies="immediately">
                  <Input
                    id="openaiApiBase"
                    placeholder="https://api.openai.com"
                    value={settings.openaiApiBase}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("openaiApiBase", e.target.value)}
                  />
                </Field>

                <Field label="OpenAI chat model" htmlFor="openaiModel" applies="immediately">
                  <Input
                    id="openaiModel"
                    placeholder="gpt-4o"
                    value={settings.openaiModel}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("openaiModel", e.target.value)}
                  />
                </Field>

                <Field
                  label="OpenAI API key"
                  htmlFor="openaiApiKey"
                  applies="immediately"
                  hint="Write-only. Leave blank to keep the current key."
                >
                  <Input
                    id="openaiApiKey"
                    type="password"
                    placeholder="sk-…"
                    value={secrets.openaiApiKey}
                    disabled={!isAdmin}
                    onChange={(e) =>
                      setSecrets((prev) => ({ ...prev, openaiApiKey: e.target.value }))
                    }
                  />
                </Field>

                <Field label="Ollama base URL" htmlFor="ollamaBaseUrl" applies="immediately">
                  <Input
                    id="ollamaBaseUrl"
                    placeholder="http://localhost:11434"
                    value={settings.ollamaBaseUrl}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("ollamaBaseUrl", e.target.value)}
                  />
                </Field>

                <Field label="Ollama model" htmlFor="ollamaModel" applies="immediately">
                  <Input
                    id="ollamaModel"
                    placeholder="qwen2:7b"
                    value={settings.ollamaModel}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("ollamaModel", e.target.value)}
                  />
                </Field>

                <Field
                  label="Volcengine base URL"
                  htmlFor="volcengineApiBase"
                  applies="restart"
                >
                  <Input
                    id="volcengineApiBase"
                    value={settings.volcengineApiBase}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("volcengineApiBase", e.target.value)}
                  />
                </Field>

                <Field
                  label="Volcengine model"
                  htmlFor="volcengineModel"
                  applies="restart"
                >
                  <Input
                    id="volcengineModel"
                    value={settings.volcengineModel}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("volcengineModel", e.target.value)}
                  />
                </Field>

                <Field
                  label="Volcengine API key"
                  htmlFor="volcengineApiKey"
                  applies="restart"
                  hint="Write-only. Leave blank to keep the current key."
                >
                  <Input
                    id="volcengineApiKey"
                    type="password"
                    value={secrets.volcengineApiKey}
                    disabled={!isAdmin}
                    onChange={(e) =>
                      setSecrets((prev) => ({
                        ...prev,
                        volcengineApiKey: e.target.value,
                      }))
                    }
                  />
                </Field>
              </CardContent>
            </Card>

            {/* Embeddings + reranking */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Retrieval</CardTitle>
                <CardDescription>
                  Changing the embedding provider only affects knowledge bases created
                  afterwards — existing collections keep the dimension they were built
                  with.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <Field
                  label="Embedding provider"
                  htmlFor="embeddingProvider"
                  applies="immediately"
                >
                  <Select
                    id="embeddingProvider"
                    options={EMBEDDING_PROVIDERS}
                    value={settings.embeddingProvider}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("embeddingProvider", e.target.value)}
                  />
                </Field>

                <Field
                  label="OpenAI embedding model"
                  htmlFor="openaiEmbeddingModel"
                  applies="immediately"
                >
                  <Input
                    id="openaiEmbeddingModel"
                    placeholder="text-embedding-3-small"
                    value={settings.openaiEmbeddingModel}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("openaiEmbeddingModel", e.target.value)}
                  />
                </Field>

                <Field
                  label="Local embedding model"
                  htmlFor="localEmbeddingModel"
                  applies="restart"
                >
                  <Input
                    id="localEmbeddingModel"
                    placeholder="BAAI/bge-m3"
                    value={settings.localEmbeddingModel}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("localEmbeddingModel", e.target.value)}
                  />
                </Field>

                <Field
                  label="Reranker"
                  htmlFor="rerankerProvider"
                  applies="immediately"
                >
                  <Select
                    id="rerankerProvider"
                    options={RERANKER_PROVIDERS}
                    value={settings.rerankerProvider}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("rerankerProvider", e.target.value)}
                  />
                </Field>

                <div className="grid grid-cols-2 gap-4">
                  <Field label="Chunk size" htmlFor="chunkSize" applies="immediately">
                    <Input
                      id="chunkSize"
                      type="number"
                      min={100}
                      value={settings.chunkSize}
                      disabled={!isAdmin}
                      onChange={(e) =>
                        updateField("chunkSize", Number(e.target.value) || 0)
                      }
                    />
                  </Field>
                  <Field
                    label="Chunk overlap"
                    htmlFor="chunkOverlap"
                    applies="immediately"
                  >
                    <Input
                      id="chunkOverlap"
                      type="number"
                      min={0}
                      value={settings.chunkOverlap}
                      disabled={!isAdmin}
                      onChange={(e) =>
                        updateField("chunkOverlap", Number(e.target.value) || 0)
                      }
                    />
                  </Field>
                </div>
                {settings.chunkOverlap >= settings.chunkSize && (
                  <p className="text-xs text-destructive">
                    Overlap must be smaller than the chunk size.
                  </p>
                )}
              </CardContent>
            </Card>

            {/* Infrastructure */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Infrastructure</CardTitle>
                <CardDescription>
                  Connection targets. All of these are read once at startup.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <Field label="Milvus host" htmlFor="milvusHost" applies="restart">
                    <Input
                      id="milvusHost"
                      value={settings.milvusHost}
                      disabled={!isAdmin}
                      onChange={(e) => updateField("milvusHost", e.target.value)}
                    />
                  </Field>
                  <Field label="Milvus port" htmlFor="milvusPort" applies="restart">
                    <Input
                      id="milvusPort"
                      type="number"
                      value={settings.milvusPort}
                      disabled={!isAdmin}
                      onChange={(e) =>
                        updateField("milvusPort", Number(e.target.value) || 0)
                      }
                    />
                  </Field>
                </div>

                <Field label="Neo4j URI" htmlFor="neo4jUri" applies="restart">
                  <Input
                    id="neo4jUri"
                    placeholder="bolt://localhost:7687"
                    value={settings.neo4jUri}
                    disabled={!isAdmin}
                    onChange={(e) => updateField("neo4jUri", e.target.value)}
                  />
                </Field>

                <div className="grid grid-cols-2 gap-4">
                  <Field
                    label="Neo4j user"
                    htmlFor="neo4jUser"
                    applies="restart"
                    hint="Write-only."
                  >
                    <Input
                      id="neo4jUser"
                      value={secrets.neo4jUser}
                      placeholder="unchanged"
                      disabled={!isAdmin}
                      onChange={(e) =>
                        setSecrets((prev) => ({ ...prev, neo4jUser: e.target.value }))
                      }
                    />
                  </Field>
                  <Field
                    label="Neo4j password"
                    htmlFor="neo4jPassword"
                    applies="restart"
                    hint="Write-only."
                  >
                    <Input
                      id="neo4jPassword"
                      type="password"
                      value={secrets.neo4jPassword}
                      placeholder="unchanged"
                      disabled={!isAdmin}
                      onChange={(e) =>
                        setSecrets((prev) => ({
                          ...prev,
                          neo4jPassword: e.target.value,
                        }))
                      }
                    />
                  </Field>
                </div>
              </CardContent>
            </Card>

            <div className="flex items-center gap-3 pt-2 pb-8">
              <Button onClick={handleSave} disabled={isSaving || !isAdmin}>
                {isSaving ? (
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                ) : (
                  <Save className="h-4 w-4 mr-2" />
                )}
                Save Settings
              </Button>
              {status === "success" && (
                <span className="flex items-center gap-1 text-sm text-green-600 dark:text-green-400">
                  <CheckCircle className="h-4 w-4" />
                  {message}
                </span>
              )}
              {status === "error" && (
                <span className="flex items-center gap-1 text-sm text-destructive">
                  <AlertCircle className="h-4 w-4" />
                  {message}
                </span>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Field({
  label,
  htmlFor,
  applies,
  hint,
  children,
}: {
  label: string;
  htmlFor: string;
  applies: "immediately" | "restart";
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <label className="text-sm font-medium" htmlFor={htmlFor}>
          {label}
        </label>
        {applies === "restart" && (
          <span className="text-[10px] uppercase tracking-wider rounded px-1.5 py-0.5 bg-muted text-muted-foreground">
            needs restart
          </span>
        )}
      </div>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}
