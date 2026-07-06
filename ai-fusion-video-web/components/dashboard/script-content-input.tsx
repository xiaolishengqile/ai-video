"use client";

import { useRef, useState } from "react";
import { Upload, Loader2, FileText, X } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  readScriptFile,
  SCRIPT_FILE_ACCEPT,
} from "@/lib/utils/read-script-file";

interface ScriptContentInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  rows?: number;
  disabled?: boolean;
  className?: string;
  textareaClassName?: string;
}

export function ScriptContentInput({
  value,
  onChange,
  placeholder,
  rows = 10,
  disabled = false,
  className,
  textareaClassName,
}: ScriptContentInputProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState("");

  const handleFileSelect = async (file: File | undefined) => {
    if (!file) return;

    setUploading(true);
    setUploadError("");
    try {
      const text = await readScriptFile(file);
      if (!text) {
        setUploadError("文件内容为空，请检查文件后重试");
        return;
      }
      onChange(text);
      setFileName(file.name);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "读取文件失败");
    } finally {
      setUploading(false);
      if (fileRef.current) {
        fileRef.current.value = "";
      }
    }
  };

  const clearFileName = () => {
    setFileName(null);
    setUploadError("");
  };

  return (
    <div className={className}>
      <div className="flex items-center justify-end gap-2 mb-2">
        {fileName && (
          <span className="text-[10px] text-muted-foreground flex items-center gap-1 max-w-[50%] truncate">
            <FileText className="h-3 w-3 shrink-0" />
            <span className="truncate">{fileName}</span>
            <button
              type="button"
              onClick={clearFileName}
              className="p-0.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
              aria-label="清除文件标记"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        )}
        <button
          type="button"
          onClick={() => fileRef.current?.click()}
          disabled={disabled || uploading}
          className={cn(
            "inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium",
            "border border-border/50 text-muted-foreground",
            "hover:text-foreground hover:bg-muted/60 transition-colors",
            "disabled:opacity-50 disabled:cursor-not-allowed"
          )}
        >
          {uploading ? (
            <Loader2 className="h-3 w-3 animate-spin" />
          ) : (
            <Upload className="h-3 w-3" />
          )}
          上传文件
        </button>
        <input
          ref={fileRef}
          type="file"
          accept={SCRIPT_FILE_ACCEPT}
          className="hidden"
          onChange={(e) => void handleFileSelect(e.target.files?.[0])}
        />
      </div>

      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        rows={rows}
        disabled={disabled || uploading}
        className={cn(
          "w-full px-3.5 py-2.5 rounded-xl text-sm resize-none",
          "bg-muted/50 border border-border/40",
          "focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/50",
          "placeholder:text-muted-foreground/50 transition-all",
          textareaClassName
        )}
      />

      {uploadError ? (
        <p className="text-xs text-destructive mt-1.5">{uploadError}</p>
      ) : (
        <p className="text-[10px] text-muted-foreground mt-1.5">
          支持粘贴文本，或上传 .txt、.md、.docx 文件
        </p>
      )}
    </div>
  );
}
