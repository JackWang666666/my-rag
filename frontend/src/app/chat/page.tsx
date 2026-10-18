"use client";

import React, { useState, useRef, useEffect, useCallback } from "react";
import {
  Send,
  Plus,
  Loader2,
  MessageSquare,
  Trash2,
  PanelLeftClose,
  PanelLeftOpen,
  Sparkles,
  FileText,
  Code,
  BookOpen,
  Globe,
  Lock,
  AlertCircle,
  X,
  Square,
  Layers,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { ChatMessage } from "@/components/chat-message";
import {
  listKnowledgeBases,
  chatStream,
  listSessions,
  deleteSession,
  getChatHistory,
} from "@/lib/api";
import type {
  KnowledgeBase,
  ChatMessage as ChatMessageType,
  Session,
} from "@/types";
import { ALL_KB_SENTINEL } from "@/types";
import { cn } from "@/lib/utils";

function getKbTypeIcon(kbType?: string) {
  switch (kbType) {
    case "book":
      return <BookOpen className="h-3.5 w-3.5" />;
    case "project":
      return <Code className="h-3.5 w-3.5" />;
    default:
      return <FileText className="h-3.5 w-3.5" />;
  }
}

function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return "Just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

export default function ChatPage() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKb, setSelectedKb] = useState<string>("");
  const [messages, setMessages] = useState<ChatMessageType[]>([]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [loadingSessions, setLoadingSessions] = useState(false);
  // Errors used to be written into the assistant bubble, which then got persisted
  // as if the model had said "Error: ...". They belong in a banner instead.
  const [error, setError] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  // Tracks IME composition (e.g. Chinese/Japanese/Korean input methods), where
  // confirming a candidate also fires a keydown with key === "Enter". Without
  // this, picking a candidate sends the message instead of just confirming it.
  const isComposingRef = useRef(false);

  // Declared before the effects that depend on them: a dependency array is
  // evaluated during render, so naming a `const` defined further down would hit
  // the temporal dead zone.
  const loadKnowledgeBases = useCallback(async () => {
    const result = await listKnowledgeBases();
    if (result.success && result.data) {
      setKnowledgeBases(result.data);
      const first = result.data[0];
      if (first) {
        // Updater form rather than reading `selectedKb`: that would make this
        // callback depend on the selection, so a re-run after the user picked a KB
        // would either see a stale value or reset their choice.
        setSelectedKb((current) => current || first.id);
      }
    }
  }, []);

  const loadSessions = useCallback(async (kbId: string) => {
    setLoadingSessions(true);
    const result = await listSessions(kbId);
    if (result.success && result.data) {
      setSessions(result.data);
    }
    setLoadingSessions(false);
  }, []);

  useEffect(() => {
    loadKnowledgeBases();
  }, [loadKnowledgeBases]);

  useEffect(() => {
    if (selectedKb) {
      loadSessions(selectedKb);
    }
  }, [selectedKb, loadSessions]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // Abort an in-flight stream when the page goes away, otherwise the fetch keeps
  // running and calls setState on an unmounted component.
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  const handleNewChat = () => {
    abortRef.current?.abort();
    setIsStreaming(false);
    setMessages([]);
    setSessionId(undefined);
    setError(null);
    // Focus the input
    setTimeout(() => textareaRef.current?.focus(), 100);
  };

  const handleSelectSession = async (session: Session) => {
    abortRef.current?.abort();
    setIsStreaming(false);
    setSessionId(session.sessionId);
    setError(null);
    const result = await getChatHistory(session.sessionId);
    if (result.success && result.data) {
      setMessages(result.data);
    } else {
      setMessages([]);
      if (!result.success) setError(result.error ?? "Failed to load history");
    }
  };

  const handleDeleteSession = async (e: React.MouseEvent, sid: string) => {
    e.stopPropagation();
    const result = await deleteSession(sid);
    if (result.success) {
      setSessions((prev) => prev.filter((s) => s.sessionId !== sid));
      if (sessionId === sid) {
        setMessages([]);
        setSessionId(undefined);
      }
    }
  };

  const handleSend = useCallback(() => {
    const question = input.trim();
    if (!question || !selectedKb || isStreaming) return;

    const now = Date.now();
    const assistantId = `${now + 1}`;
    const userMessage: ChatMessageType = {
      id: `${now}`,
      role: "user",
      content: question,
      timestamp: new Date().toISOString(),
    };
    const assistantMessage: ChatMessageType = {
      id: assistantId,
      role: "assistant",
      content: "",
      timestamp: new Date().toISOString(),
    };

    setMessages((prev) => [...prev, userMessage, assistantMessage]);
    setInput("");
    setError(null);
    setIsStreaming(true);

    // Reset textarea height
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }

    /**
     * Immutably patch the assistant message we just appended. Addressing it by
     * id rather than "the last element" keeps this correct if anything else
     * appends in the meantime, and copying the object (instead of the previous
     * code's in-place `lastMsg.content += chunk`) is what lets React see the
     * change — mutating state and re-wrapping the array only happened to work.
     */
    const patchAssistant = (patch: Partial<ChatMessageType>) => {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === assistantId ? { ...message, ...patch } : message
        )
      );
    };

    abortRef.current = chatStream(
      {
        question,
        knowledgeBaseId: selectedKb,
        // Omitted on a new chat: the backend creates the session and reports the
        // id back on the `session` event, so no extra round-trip is needed.
        sessionId,
      },
      {
        onSession: (newSessionId) => {
          setSessionId((current) => current ?? newSessionId);
        },
        onMetadata: (metadata) => {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === assistantId
                ? {
                    ...message,
                    metadata: { ...message.metadata, ...metadata },
                    route: metadata.route ?? message.route,
                    confidence: metadata.confidence ?? message.confidence,
                  }
                : message
            )
          );
        },
        onConfidence: (confidence) => patchAssistant({ confidence }),
        onStage: (stage) => {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === assistantId
                ? { ...message, stages: [...(message.stages ?? []), stage] }
                : message
            )
          );
        },
        onChunk: (chunk) => {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === assistantId
                ? { ...message, content: message.content + chunk }
                : message
            )
          );
        },
        onSources: (sources) => patchAssistant({ sources }),
        onDone: (sources) => {
          if (sources) patchAssistant({ sources });
          setIsStreaming(false);
          // Titles and timestamps are derived server-side, so re-read them.
          if (selectedKb) loadSessions(selectedKb);
        },
        onError: (message) => {
          setError(message);
          setIsStreaming(false);
        },
      }
    );
  }, [input, selectedKb, isStreaming, sessionId, loadSessions]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      e.key === "Enter" &&
      !e.shiftKey &&
      !e.nativeEvent.isComposing &&
      !isComposingRef.current
    ) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleCompositionStart = () => {
    isComposingRef.current = true;
  };

  const handleCompositionEnd = () => {
    isComposingRef.current = false;
  };

  const handleStop = () => {
    abortRef.current?.abort();
    setIsStreaming(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value);
    // Auto-resize textarea
    const textarea = e.target;
    textarea.style.height = "auto";
    textarea.style.height = Math.min(textarea.scrollHeight, 200) + "px";
  };

  const selectedKbData = knowledgeBases.find((kb) => kb.id === selectedKb);

  return (
    <div className="flex h-full">
      {/* Chat sidebar - sessions list */}
      <div
        className={cn(
          "border-r border-border flex flex-col bg-card/50 transition-all duration-300 overflow-hidden",
          sidebarOpen ? "w-72" : "w-0"
        )}
      >
        <div className="flex flex-col h-full min-w-[288px]">
          {/* New chat button */}
          <div className="p-3 border-b border-border">
            <Button
              variant="outline"
              className="w-full justify-start gap-2 h-10"
              onClick={handleNewChat}
            >
              <Plus className="h-4 w-4" />
              New Chat
            </Button>
          </div>

          {/* KB selector */}
          <div className="px-3 py-3 border-b border-border">
            <label className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider mb-2 block">
              Knowledge Base
            </label>
            <div className="space-y-1">
              {/* All-my-KBs mode */}
              {knowledgeBases.filter((kb) => !kb.isGlobal).length > 0 && (
                <button
                  onClick={() => {
                    setSelectedKb(ALL_KB_SENTINEL);
                    handleNewChat();
                  }}
                  className={cn(
                    "w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors text-left",
                    selectedKb === ALL_KB_SENTINEL
                      ? "bg-primary/10 text-primary font-medium"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  )}
                >
                  <Layers className="h-3.5 w-3.5" />
                  <span className="truncate">全部我的知识库</span>
                </button>
              )}
              {/* Global KBs */}
              {knowledgeBases.filter((kb) => kb.isGlobal).length > 0 && (
                <>
                  <p className="text-[10px] font-medium text-muted-foreground/70 uppercase tracking-wider px-3 pt-1 flex items-center gap-1">
                    <Globe className="h-3 w-3" /> 全局知识库
                  </p>
                  {knowledgeBases
                    .filter((kb) => kb.isGlobal)
                    .map((kb) => (
                      <button
                        key={kb.id}
                        onClick={() => {
                          setSelectedKb(kb.id);
                          handleNewChat();
                        }}
                        className={cn(
                          "w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors text-left",
                          selectedKb === kb.id
                            ? "bg-primary/10 text-primary font-medium"
                            : "text-muted-foreground hover:bg-muted hover:text-foreground"
                        )}
                      >
                        {getKbTypeIcon(kb.kbType)}
                        <span className="truncate">{kb.name}</span>
                        <Globe className="h-3 w-3 flex-shrink-0 text-violet-500 ml-auto" />
                      </button>
                    ))}
                </>
              )}
              {/* Private KBs */}
              {knowledgeBases.filter((kb) => !kb.isGlobal).length > 0 && (
                <>
                  <p className="text-[10px] font-medium text-muted-foreground/70 uppercase tracking-wider px-3 pt-2 flex items-center gap-1">
                    <Lock className="h-3 w-3" /> 我的知识库
                  </p>
                  {knowledgeBases
                    .filter((kb) => !kb.isGlobal)
                    .map((kb) => (
                      <button
                        key={kb.id}
                        onClick={() => {
                          setSelectedKb(kb.id);
                          handleNewChat();
                        }}
                        className={cn(
                          "w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors text-left",
                          selectedKb === kb.id
                            ? "bg-primary/10 text-primary font-medium"
                            : "text-muted-foreground hover:bg-muted hover:text-foreground"
                        )}
                      >
                        {getKbTypeIcon(kb.kbType)}
                        <span className="truncate">{kb.name}</span>
                      </button>
                    ))}
                </>
              )}
              {/* Fallback: show all if none have isGlobal set */}
              {knowledgeBases.filter((kb) => kb.isGlobal).length === 0 &&
                knowledgeBases.filter((kb) => !kb.isGlobal).length === 0 &&
                knowledgeBases.map((kb) => (
                  <button
                    key={kb.id}
                    onClick={() => {
                      setSelectedKb(kb.id);
                      handleNewChat();
                    }}
                    className={cn(
                      "w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors text-left",
                      selectedKb === kb.id
                        ? "bg-primary/10 text-primary font-medium"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground"
                    )}
                  >
                    {getKbTypeIcon(kb.kbType)}
                    <span className="truncate">{kb.name}</span>
                  </button>
                ))}
            </div>
          </div>

          {/* Sessions list */}
          <div className="flex-1 overflow-y-auto">
            <div className="px-3 py-2">
              <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
                Recent Chats
              </p>
            </div>
            {loadingSessions ? (
              <div className="flex justify-center py-4">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
              </div>
            ) : sessions.length === 0 ? (
              <div className="px-3 py-4 text-center">
                <p className="text-xs text-muted-foreground">No conversations yet</p>
              </div>
            ) : (
              <div className="px-2 space-y-0.5">
                {sessions.map((session) => (
                  <div
                    key={session.sessionId}
                    onClick={() => handleSelectSession(session)}
                    className={cn(
                      "group flex items-center gap-2 px-3 py-2.5 rounded-md cursor-pointer transition-colors",
                      sessionId === session.sessionId
                        ? "bg-primary/10 text-foreground"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground"
                    )}
                  >
                    <MessageSquare className="h-3.5 w-3.5 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm truncate">
                        {session.title || "Untitled Chat"}
                      </p>
                      <p className="text-[11px] text-muted-foreground">
                        {formatRelativeTime(session.updatedAt || session.createdAt)}
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0"
                      onClick={(e) => handleDeleteSession(e, session.sessionId)}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Main chat area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Top bar */}
        <div className="border-b border-border px-4 py-3 flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8"
            onClick={() => setSidebarOpen(!sidebarOpen)}
          >
            {sidebarOpen ? (
              <PanelLeftClose className="h-4 w-4" />
            ) : (
              <PanelLeftOpen className="h-4 w-4" />
            )}
          </Button>

          {selectedKb === ALL_KB_SENTINEL ? (
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-muted/50 text-sm">
                <Layers className="h-3.5 w-3.5" />
                <span className="font-medium">全部我的知识库</span>
              </div>
            </div>
          ) : (
            selectedKbData && (
              <div className="flex items-center gap-2">
                <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-muted/50 text-sm">
                  {getKbTypeIcon(selectedKbData.kbType)}
                  <span className="font-medium">{selectedKbData.name}</span>
                </div>
              </div>
            )
          )}

          {!selectedKb && knowledgeBases.length > 0 && (
            <span className="text-sm text-muted-foreground">
              Select a knowledge base to start chatting
            </span>
          )}
        </div>

        {/* Messages */}
        <div className="flex-1 overflow-y-auto">
          {messages.length === 0 ? (
            <div className="flex items-center justify-center h-full">
              <div className="text-center space-y-6 max-w-lg px-4">
                <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-violet-500/20 to-indigo-600/20 flex items-center justify-center mx-auto">
                  <Sparkles className="h-8 w-8 text-primary" />
                </div>
                <div className="space-y-2">
                  <h2 className="text-2xl font-semibold tracking-tight">
                    RAG Chat
                  </h2>
                  <p className="text-muted-foreground text-sm leading-relaxed max-w-md mx-auto">
                    Ask questions about your knowledge base. The AI retrieves relevant
                    documents and provides accurate answers with source references.
                  </p>
                </div>
                {knowledgeBases.length === 0 && (
                  <div className="rounded-lg border border-border bg-muted/30 p-4">
                    <p className="text-sm text-muted-foreground">
                      No knowledge bases found. Create one in the Knowledge Base
                      section first.
                    </p>
                  </div>
                )}
                {selectedKb && (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-w-md mx-auto">
                    {[
                      "What topics are covered?",
                      "Summarize the main concepts",
                      "What are the key takeaways?",
                      "How does this relate to...?",
                    ].map((suggestion) => (
                      <button
                        key={suggestion}
                        onClick={() => {
                          setInput(suggestion);
                          textareaRef.current?.focus();
                        }}
                        className="text-left px-4 py-3 rounded-lg border border-border hover:bg-muted/50 hover:border-primary/30 transition-colors text-sm text-muted-foreground hover:text-foreground"
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="max-w-4xl mx-auto">
              {messages.map((message, index) => (
                <ChatMessage
                  key={message.id}
                  message={message}
                  isStreaming={
                    isStreaming &&
                    index === messages.length - 1 &&
                    message.role === "assistant"
                  }
                  sessionId={sessionId}
                  messageIndex={index}
                />
              ))}
              <div ref={messagesEndRef} className="h-4" />
            </div>
          )}
        </div>

        {/* Input area */}
        <div className="border-t border-border p-4 bg-background/80 backdrop-blur-sm">
          <div className="max-w-3xl mx-auto">
            {error && (
              <div className="mb-3 flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
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
            <div
              className={cn(
                "flex items-end gap-2 rounded-xl border border-border p-3 transition-colors",
                "bg-muted/30 focus-within:border-primary/50 focus-within:bg-muted/50"
              )}
            >
              <textarea
                ref={textareaRef}
                value={input}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                onCompositionStart={handleCompositionStart}
                onCompositionEnd={handleCompositionEnd}
                placeholder={
                  selectedKb
                    ? "Ask a question about your knowledge base..."
                    : "Select a knowledge base to start"
                }
                disabled={!selectedKb || isStreaming}
                rows={1}
                className="flex-1 resize-none bg-transparent border-0 outline-none text-sm px-1 py-1 max-h-[200px] placeholder:text-muted-foreground/60 disabled:opacity-50"
              />
              <Button
                size="icon"
                onClick={isStreaming ? handleStop : handleSend}
                disabled={isStreaming ? false : !input.trim() || !selectedKb}
                aria-label={isStreaming ? "Stop generating" : "Send message"}
                className={cn(
                  "flex-shrink-0 h-9 w-9 rounded-lg transition-all",
                  isStreaming || (input.trim() && selectedKb)
                    ? "bg-primary hover:bg-primary/90 shadow-sm"
                    : ""
                )}
              >
                {isStreaming ? (
                  <Square className="h-3.5 w-3.5 fill-current" />
                ) : (
                  <Send className="h-4 w-4" />
                )}
              </Button>
            </div>
            <p className="text-[11px] text-muted-foreground/60 text-center mt-2">
              Enter to send &middot; Shift+Enter for new line
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
