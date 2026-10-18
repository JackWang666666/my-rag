"use client";

import React, { useState, useCallback, useRef } from "react";
import { Upload, File, X, AlertCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Extensions both backends can actually parse.
 *
 * Everything not in this list is read as UTF-8 text by both, which is fine for an
 * unlisted source-code extension and useless for an unlisted binary format. The
 * Spring backend additionally handles `.pptx`, `.odt`, `.rtf` and `.epub` through
 * Tika, but the Python backend would store mojibake for those, so they stay out —
 * the two backends must ingest the same files the same way.
 */
const ACCEPTED_TYPES = [
  ".pdf",
  ".docx",
  ".md",
  ".txt",
  ".log",
  ".csv",
  ".xlsx",
  ".xls",
  ".py",
  ".js",
  ".jsx",
  ".ts",
  ".tsx",
  ".java",
  ".kt",
  ".go",
  ".rs",
  ".rb",
  ".php",
  ".cpp",
  ".c",
  ".h",
  ".cs",
  ".sh",
  ".sql",
  ".json",
  ".yaml",
  ".yml",
  ".toml",
  ".xml",
  ".html",
  ".css",
];

/** Refuse before uploading rather than after: both backends cap the body size. */
const MAX_FILE_SIZE = 50 * 1024 * 1024;

interface FileUploadProps {
  onUpload: (files: File[]) => void;
  isUploading?: boolean;
  progress?: number;
}

interface FileItem {
  file: File;
  /**
   * There is no "done": this component never learns the outcome, the page does.
   * The old version declared `uploading`/`done` statuses that nothing ever set,
   * so the success tick was dead code and every accepted file looked pending
   * forever.
   */
  status: "accepted" | "rejected";
  error?: string;
}

export function FileUpload({ onUpload, isUploading, progress }: FileUploadProps) {
  const [dragOver, setDragOver] = useState(false);
  const [files, setFiles] = useState<FileItem[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  const reject = (file: File): string | null => {
    const dot = file.name.lastIndexOf(".");
    const ext = dot === -1 ? "" : file.name.slice(dot).toLowerCase();
    if (!ACCEPTED_TYPES.includes(ext)) {
      return ext ? `Unsupported file type ${ext}` : "File has no extension";
    }
    if (file.size === 0) return "File is empty";
    if (file.size > MAX_FILE_SIZE) {
      return `Larger than ${MAX_FILE_SIZE / (1024 * 1024)} MB`;
    }
    return null;
  };

  const handleFiles = useCallback(
    (fileList: FileList | null) => {
      if (!fileList || fileList.length === 0) return;

      const accepted: File[] = [];
      const items: FileItem[] = Array.from(fileList).map((file) => {
        const error = reject(file);
        if (error) return { file, status: "rejected", error };
        accepted.push(file);
        return { file, status: "accepted" };
      });

      // Replace rather than append: the list describes the current selection, and
      // appending made rejected files from earlier attempts pile up forever.
      setFiles(items);
      if (accepted.length > 0) {
        onUpload(accepted);
      }
    },
    [onUpload]
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragOver(false);
      handleFiles(e.dataTransfer.files);
    },
    [handleFiles]
  );

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);

  const removeFile = (index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  };

  return (
    <div className="space-y-4">
      {/* Drop zone */}
      <div
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onClick={() => inputRef.current?.click()}
        className={cn(
          "border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors",
          dragOver
            ? "border-primary bg-primary/5"
            : "border-border hover:border-primary/50 hover:bg-muted/50"
        )}
      >
        <Upload
          className={cn(
            "h-10 w-10 mx-auto mb-3",
            dragOver ? "text-primary" : "text-muted-foreground"
          )}
        />
        <p className="text-sm font-medium">
          Drop files here or click to browse
        </p>
        <p className="text-xs text-muted-foreground mt-1">
          Supports: PDF, DOCX, MD, TXT, CSV, XLSX, and code files
        </p>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPTED_TYPES.join(",")}
          onChange={(e) => {
            handleFiles(e.target.files);
            // Clearing the value lets the same file be picked again; without this
            // a retry after a failed upload fired no change event at all.
            e.target.value = "";
          }}
          className="hidden"
        />
      </div>

      {/* Upload progress */}
      {isUploading && progress !== undefined && (
        <div className="space-y-2">
          <div className="flex justify-between text-sm">
            <span>Uploading...</span>
            <span>{progress}%</span>
          </div>
          <div className="h-2 bg-muted rounded-full overflow-hidden">
            <div
              className="h-full bg-primary transition-all duration-300 rounded-full"
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      )}

      {/* File list */}
      {files.length > 0 && (
        <div className="space-y-2">
          {files.map((item, index) => (
            <div
              key={index}
              className="flex items-center gap-3 p-3 rounded-md bg-muted/50 border border-border"
            >
              <File className="h-4 w-4 text-muted-foreground flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium truncate">
                  {item.file.name}
                </p>
                <p className="text-xs text-muted-foreground">
                  {formatFileSize(item.file.size)}
                </p>
              </div>
              {item.status === "accepted" && isUploading && (
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground flex-shrink-0" />
              )}
              {item.status === "rejected" && (
                <div className="flex items-center gap-1">
                  <AlertCircle className="h-4 w-4 text-destructive flex-shrink-0" />
                  <span className="text-xs text-destructive">{item.error}</span>
                </div>
              )}
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6 flex-shrink-0"
                onClick={(e) => {
                  e.stopPropagation();
                  removeFile(index);
                }}
              >
                <X className="h-3 w-3" />
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
