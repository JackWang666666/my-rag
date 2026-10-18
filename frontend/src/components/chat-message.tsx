"use client";

import React, { useState } from "react";
import ReactMarkdown from "react-markdown";
import { User, Bot, ChevronDown, ChevronRight, Copy, Check, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { MessageFeedback } from "@/components/message-feedback";
import { ConfidenceIndicator } from "@/components/confidence-indicator";
import { SuggestedQuestions } from "@/components/suggested-questions";
import type { ChatMessage as ChatMessageType, StageEvent } from "@/types";

const STAGE_LABELS: Record<StageEvent["stage"], string> = {
  analyzing: "分析问题",
  retrieving: "检索相关文档",
  graph: "查询知识图谱",
  generating: "生成回答",
};

/** The in-progress / completed timeline of pipeline stages for one answer. */
function StageTimeline({ stages, isStreaming }: { stages: StageEvent[]; isStreaming?: boolean }) {
  const [expanded, setExpanded] = useState(false);

  // The most recent stage with a "start" but no matching "done" yet.
  const active = isStreaming
    ? stages.find(
        (s, i) =>
          s.status === "start" &&
          !stages.slice(i + 1).some((other) => other.stage === s.stage && other.status === "done")
      )
    : undefined;

  const completed = stages.filter((s): s is StageEvent & { elapsedMs: number } =>
    s.status === "done" && s.elapsedMs != null
  );

  if (!active && completed.length === 0) return null;

  if (active) {
    return (
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" />
        <span>{STAGE_LABELS[active.stage]}...</span>
      </div>
    );
  }

  return (
    <div className="pt-1">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
      >
        {expanded ? (
          <ChevronDown className="h-3.5 w-3.5" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5" />
        )}
        <span>
          {completed
            .map((s) => `${STAGE_LABELS[s.stage]} ${(s.elapsedMs / 1000).toFixed(1)}s`)
            .join(" · ")}
        </span>
      </button>

      {expanded && (
        <div className="mt-2 space-y-1 animate-in slide-in-from-top-2 duration-200">
          {completed.map((s, idx) => (
            <div
              key={idx}
              className="flex items-center justify-between gap-2 rounded-lg border border-border bg-muted/30 px-3 py-1.5 text-xs"
            >
              <span className="text-muted-foreground">{STAGE_LABELS[s.stage]}</span>
              <span className="tabular-nums text-muted-foreground/70">
                {(s.elapsedMs / 1000).toFixed(2)}s
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

interface ChatMessageProps {
  message: ChatMessageType;
  isStreaming?: boolean;
  sessionId?: string;
  messageIndex?: number;
  onSuggestedQuestionClick?: (question: string) => void;
}

export function ChatMessage({
  message,
  isStreaming,
  sessionId,
  messageIndex,
  onSuggestedQuestionClick,
}: ChatMessageProps) {
  const isUser = message.role === "user";
  const [showSources, setShowSources] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(message.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (isUser) {
    return (
      <div className="flex justify-end px-4 py-4 md:px-6">
        <div className="max-w-[80%] md:max-w-[70%]">
          <div className="bg-primary text-primary-foreground rounded-2xl rounded-br-md px-4 py-3 shadow-sm">
            <p className="text-sm whitespace-pre-wrap leading-relaxed">
              {message.content}
            </p>
          </div>
          <div className="flex justify-end mt-1">
            <span className="text-[11px] text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity">
              {message.timestamp &&
                new Date(message.timestamp).toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
            </span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="group px-4 py-4 md:px-6 hover:bg-muted/20 transition-colors">
      <div className="flex gap-3 max-w-full">
        {/* Avatar */}
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center shadow-sm">
          <Bot className="h-4 w-4 text-white" />
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0 space-y-2">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">Assistant</span>
            <span className="text-[11px] text-muted-foreground">
              {message.timestamp &&
                new Date(message.timestamp).toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
            </span>
          </div>

          {/* Stage timeline: active-stage spinner while streaming, collapsible
              elapsed-time summary once the answer settles. */}
          {message.stages && message.stages.length > 0 && (
            <StageTimeline stages={message.stages} isStreaming={isStreaming} />
          )}

          {/* Message content */}
          <div className="text-sm leading-relaxed">
            <div className="markdown-content prose-sm">
              <ReactMarkdown>{message.content}</ReactMarkdown>
              {isStreaming && (
                <span className="inline-block w-2 h-5 bg-primary/70 animate-pulse ml-0.5 rounded-sm" />
              )}
            </div>
          </div>

          {/* Action buttons */}
          {!isStreaming && message.content && (
            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <Button
                variant="ghost"
                size="sm"
                className="h-7 px-2 text-muted-foreground hover:text-foreground"
                onClick={handleCopy}
              >
                {copied ? (
                  <Check className="h-3.5 w-3.5 mr-1 text-green-500" />
                ) : (
                  <Copy className="h-3.5 w-3.5 mr-1" />
                )}
                <span className="text-xs">{copied ? "Copied" : "Copy"}</span>
              </Button>
            </div>
          )}

          {/* Sources section */}
          {message.sources && message.sources.length > 0 && (
            <div className="pt-1">
              <button
                onClick={() => setShowSources(!showSources)}
                className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                {showSources ? (
                  <ChevronDown className="h-3.5 w-3.5" />
                ) : (
                  <ChevronRight className="h-3.5 w-3.5" />
                )}
                <span>{message.sources.length} source{message.sources.length > 1 ? "s" : ""}</span>
              </button>

              {showSources && (
                <div className="mt-2 space-y-2 animate-in slide-in-from-top-2 duration-200">
                  {message.sources.map((source, idx) => (
                    <div
                      key={idx}
                      className="rounded-lg border border-border bg-muted/30 p-3 space-y-2"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-medium truncate">
                          {source.source || `Source ${idx + 1}`}
                        </span>
                        {source.score != null && (
                          <div className="flex items-center gap-1.5 flex-shrink-0">
                            <div className="w-16 h-1.5 bg-muted rounded-full overflow-hidden">
                              <div
                                className={cn(
                                  "h-full rounded-full transition-all",
                                  source.score > 0.8
                                    ? "bg-green-500"
                                    : source.score > 0.5
                                    ? "bg-yellow-500"
                                    : "bg-orange-500"
                                )}
                                style={{ width: `${Math.round(source.score * 100)}%` }}
                              />
                            </div>
                            <span className="text-[10px] text-muted-foreground tabular-nums">
                              {Math.round(source.score * 100)}%
                            </span>
                          </div>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground line-clamp-3 leading-relaxed">
                        {source.text || "No preview available"}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Confidence indicator */}
          {!isStreaming && (message.confidence != null || message.metadata?.confidence != null) && (
            <ConfidenceIndicator
              score={message.confidence ?? message.metadata?.confidence ?? 0}
              route={message.route ?? message.metadata?.route}
            />
          )}

          {/* Feedback buttons */}
          {!isStreaming && message.content && sessionId && messageIndex != null && (
            <MessageFeedback sessionId={sessionId} messageIndex={messageIndex} />
          )}

          {/* Suggested follow-up questions */}
          {!isStreaming &&
            message.content &&
            message.suggestedQuestions &&
            message.suggestedQuestions.length > 0 && (
              <SuggestedQuestions
                questions={message.suggestedQuestions}
                onSelect={(question) => onSuggestedQuestionClick?.(question)}
              />
            )}
        </div>
      </div>
    </div>
  );
}
