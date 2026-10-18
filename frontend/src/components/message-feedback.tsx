"use client";

import React, { useState } from "react";
import { ThumbsUp, ThumbsDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { submitFeedback } from "@/lib/api";
import { cn } from "@/lib/utils";

interface MessageFeedbackProps {
  sessionId: string;
  messageIndex: number;
}

export function MessageFeedback({ sessionId, messageIndex }: MessageFeedbackProps) {
  const [rating, setRating] = useState<"up" | "down" | null>(null);
  const [showComment, setShowComment] = useState(false);
  const [comment, setComment] = useState("");
  const [submitted, setSubmitted] = useState(false);
  // A rejected request used to still render "感谢反馈", so nothing was recorded and
  // the user was told it had been.
  const [error, setError] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);

  const handleFeedback = async (value: "up" | "down") => {
    setRating(value);
    setError(null);
    setIsSending(true);
    // Both backends upsert on (sessionId, messageIndex), so changing a vote or
    // adding a comment later replaces the row instead of adding another.
    const result = await submitFeedback({
      sessionId,
      messageIndex,
      rating: value,
    });
    setIsSending(false);
    if (result.success) {
      setSubmitted(true);
    } else {
      setRating(null);
      setError(result.error ?? "提交失败");
    }
  };

  const handleCommentSubmit = async () => {
    if (!comment.trim() || !rating) return;
    setError(null);
    setIsSending(true);
    const result = await submitFeedback({
      sessionId,
      messageIndex,
      rating,
      comment: comment.trim(),
    });
    setIsSending(false);
    if (result.success) {
      setShowComment(false);
      setSubmitted(true);
    } else {
      setError(result.error ?? "提交失败");
    }
  };

  if (submitted && !showComment) {
    return (
      <div className="flex items-center gap-1.5 mt-1 animate-in fade-in duration-200">
        <span className="text-xs text-muted-foreground">
          {rating === "up" ? "👍" : "👎"} 感谢反馈
        </span>
        {!comment && (
          <button
            onClick={() => setShowComment(true)}
            className="text-xs text-primary/70 hover:text-primary transition-colors"
          >
            补充说明
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="mt-2">
      <div className="flex items-center gap-1">
        <Button
          variant="ghost"
          size="icon"
          className={cn(
            "h-7 w-7 transition-colors",
            rating === "up" && "text-green-500 bg-green-500/10"
          )}
          disabled={isSending}
          onClick={() => handleFeedback("up")}
        >
          <ThumbsUp className="h-3.5 w-3.5" />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className={cn(
            "h-7 w-7 transition-colors",
            rating === "down" && "text-red-500 bg-red-500/10"
          )}
          disabled={isSending}
          onClick={() => handleFeedback("down")}
        >
          <ThumbsDown className="h-3.5 w-3.5" />
        </Button>
      </div>

      {error && <p className="text-xs text-destructive mt-1">{error}</p>}

      {showComment && (
        <div className="mt-2 flex gap-2 animate-in slide-in-from-top-2 duration-200">
          <input
            type="text"
            placeholder="有什么建议？（可选）"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleCommentSubmit()}
            className="flex-1 text-xs px-3 py-1.5 rounded-md border border-border bg-background focus:outline-none focus:ring-1 focus:ring-primary/50 transition-shadow"
          />
          <Button
            size="sm"
            className="h-7 text-xs"
            disabled={isSending || !comment.trim()}
            onClick={handleCommentSubmit}
          >
            提交
          </Button>
        </div>
      )}
    </div>
  );
}
