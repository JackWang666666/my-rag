"use client";

import React from "react";
import { Sparkles } from "lucide-react";

interface SuggestedQuestionsProps {
  questions: string[];
  onSelect: (question: string) => void;
}

export function SuggestedQuestions({ questions, onSelect }: SuggestedQuestionsProps) {
  if (questions.length === 0) return null;

  return (
    <div className="pt-2 animate-in fade-in slide-in-from-top-1 duration-300">
      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground mb-2">
        <Sparkles className="h-3.5 w-3.5" />
        <span>猜你想问</span>
      </div>
      <div className="flex flex-col gap-1.5">
        {questions.map((question, idx) => (
          <button
            key={idx}
            onClick={() => onSelect(question)}
            className="text-left px-3 py-2 rounded-lg border border-border hover:bg-muted/50 hover:border-primary/30 transition-colors text-xs text-muted-foreground hover:text-foreground"
          >
            {question}
          </button>
        ))}
      </div>
    </div>
  );
}
