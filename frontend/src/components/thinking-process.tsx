"use client";

import React, { useState } from "react";
import { ChevronDown, ChevronRight, BrainCircuit } from "lucide-react";
import type { LlmCallRecord } from "@/types";

const STEP_LABELS: Record<string, string> = {
  analyzing: "路由分析",
  generating: "生成回答",
  suggesting: "猜你想问",
  grounding: "忠实性校验",
  regenerate: "重新生成",
};

function stepLabel(step: string): string {
  return STEP_LABELS[step] ?? step;
}

function formatTokens(call: LlmCallRecord): string | null {
  if (call.totalTokens == null) return null;
  return `${call.totalTokens} tokens`;
}

/** The multi-step "thinking process" behind one answer: every independent LLM
 * call, its token usage (when known) and duration, in the order it happened. */
export function ThinkingProcess({ calls }: { calls?: LlmCallRecord[] }) {
  const [expanded, setExpanded] = useState(false);

  if (!calls || calls.length === 0) return null;

  const totalTokens = calls.reduce((sum, c) => sum + (c.totalTokens ?? 0), 0);
  const hasAnyTokens = calls.some((c) => c.totalTokens != null);

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
        <BrainCircuit className="h-3.5 w-3.5" />
        <span>
          思考过程 · {calls.length} 步
          {hasAnyTokens ? ` · 共 ${totalTokens} tokens` : ""}
        </span>
      </button>

      {expanded && (
        <div className="mt-2 space-y-1.5 animate-in slide-in-from-top-2 duration-200">
          {calls.map((call, idx) => (
            <div
              key={idx}
              className="rounded-lg border border-border bg-muted/30 p-3 space-y-1"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium">{stepLabel(call.step)}</span>
                <div className="flex items-center gap-2 flex-shrink-0 text-[10px] text-muted-foreground tabular-nums">
                  {formatTokens(call) && <span>{formatTokens(call)}</span>}
                  <span>{(call.durationMs / 1000).toFixed(2)}s</span>
                </div>
              </div>
              {call.model && (
                <p className="text-[10px] text-muted-foreground/70">{call.model}</p>
              )}
              {call.contentPreview && (
                <p className="text-xs text-muted-foreground leading-relaxed line-clamp-3">
                  {call.contentPreview}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
