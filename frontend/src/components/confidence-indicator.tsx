"use client";

import React from "react";
import { cn } from "@/lib/utils";
import { Shield, ShieldAlert, ShieldCheck } from "lucide-react";

interface ConfidenceIndicatorProps {
  score: number; // 0-1
  route?: string;
}

export function ConfidenceIndicator({ score, route }: ConfidenceIndicatorProps) {
  const percentage = Math.round(score * 100);

  let color = "text-green-500";
  let bgColor = "bg-green-500";
  let label = "高置信度";
  let Icon = ShieldCheck;

  if (score < 0.5) {
    color = "text-orange-500";
    bgColor = "bg-orange-500";
    label = "低置信度";
    Icon = ShieldAlert;
  } else if (score < 0.75) {
    color = "text-yellow-500";
    bgColor = "bg-yellow-500";
    label = "中等置信度";
    Icon = Shield;
  }

  const routeLabels: Record<string, string> = {
    rag: "文档检索",
    graph: "图谱查询",
    hybrid: "混合检索",
    direct: "直接回答",
  };

  return (
    <div className="flex items-center gap-3 text-xs text-muted-foreground mt-2 px-1 animate-in fade-in duration-300">
      <div className="flex items-center gap-1.5">
        <Icon className={cn("h-3.5 w-3.5", color)} />
        <span className={color}>{label}</span>
        <div className="w-16 h-1.5 rounded-full bg-muted overflow-hidden">
          <div
            className={cn("h-full rounded-full transition-all duration-500", bgColor)}
            style={{ width: `${percentage}%` }}
          />
        </div>
        <span className="text-muted-foreground/70">{percentage}%</span>
      </div>
      {route && (
        <span className="px-1.5 py-0.5 rounded bg-muted text-muted-foreground text-[11px]">
          {routeLabels[route] || route}
        </span>
      )}
    </div>
  );
}
