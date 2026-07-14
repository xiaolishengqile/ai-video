"use client";

import { useRef, useState } from "react";
import { Loader2, Upload, X } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  assetApi,
  type AssetFolderImportPreviewItem,
  type AssetFolderImportResult,
} from "@/lib/api/asset";
import { removeFileFromPreview } from "@/lib/asset-folder-import-preview.mjs";
import { openDirectoryPicker } from "@/lib/directory-input.mjs";

type SelectedFile = { file: File; relativePath: string };

const TYPE_OPTIONS = [
  { value: "character", label: "角色" },
  { value: "scene", label: "场景" },
  { value: "prop", label: "道具" },
];
const MAX_CHUNK_BYTES = 450 * 1024 * 1024;
// 每张图会提交 files 和 relativePaths 两个 multipart part，批次数量保持可控。
const MAX_FILES_PER_CHUNK = 20;

function isImage(file: File) {
  return file.type.startsWith("image/") || /\.(png|jpe?g|webp|gif)$/i.test(file.name);
}

function chunks(files: SelectedFile[], preview: AssetFolderImportPreviewItem[]) {
  const order = new Map(preview.map((item) => [item.relativePath, item.kind === "root" ? 0 : 1]));
  const sorted = [...files].sort((a, b) => (order.get(a.relativePath) ?? 1) - (order.get(b.relativePath) ?? 1));
  const groups: SelectedFile[][] = [];
  let current: SelectedFile[] = [];
  let size = 0;
  for (const item of sorted) {
    if (item.file.size > MAX_CHUNK_BYTES) throw new Error(`${item.relativePath} 超过 80MB，无法安全上传`);
    if (current.length && (current.length >= MAX_FILES_PER_CHUNK || size + item.file.size > MAX_CHUNK_BYTES)) {
      groups.push(current);
      current = [];
      size = 0;
    }
    current.push(item);
    size += item.file.size;
  }
  if (current.length) groups.push(current);
  return groups;
}

export default function AssetFolderImportDialog({
  projectId,
  onImported,
  onClose,
}: {
  projectId: number;
  onImported: () => void;
  onClose: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [type, setType] = useState("");
  const [files, setFiles] = useState<SelectedFile[]>([]);
  const [preview, setPreview] = useState<AssetFolderImportPreviewItem[]>([]);
  const [results, setResults] = useState<AssetFolderImportResult["results"]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const hasInvalidPreview = preview.some((item) => item.reason !== null);

  const loadPreview = async (next: SelectedFile[], nextType = type, clearPreview = true) => {
    setFiles(next);
    if (clearPreview) setPreview([]);
    setResults([]);
    setError("");
    if (!nextType || !next.length) return;
    setLoading(true);
    try {
      const response = await assetApi.previewFolderImport({
        projectId,
        type: nextType,
        files: next.map(({ file, relativePath }) => ({ relativePath, originalName: file.name })),
      });
      setPreview(response.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : "预览失败");
    } finally {
      setLoading(false);
    }
  };

  const chooseFiles = (event: React.ChangeEvent<HTMLInputElement>) => {
    const next = Array.from(event.target.files ?? [])
      .filter(isImage)
      .map((file) => ({ file, relativePath: file.webkitRelativePath || file.name }));
    void loadPreview(next);
    event.target.value = "";
  };

  const removeFile = (relativePath: string) => {
    const next = removeFileFromPreview(files, preview, relativePath);
    setPreview(next.preview);
    void loadPreview(next.files, type, false);
  };

  const upload = async () => {
    if (!type || !files.length) return;
    setLoading(true);
    setError("");
    try {
      const imported: AssetFolderImportResult["results"] = [];
      for (const chunk of chunks(files, preview)) {
        const response = await assetApi.importFolderChunk({
          projectId,
          type,
          files: chunk.map((item) => item.file),
          relativePaths: chunk.map((item) => item.relativePath),
        });
        imported.push(...response.results);
      }
      setResults(imported);
      if (imported.some((item) => item.status === "success")) onImported();
    } catch (err) {
      setError(err instanceof Error ? err.message : "导入失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl max-h-[calc(100vh-2rem)] flex flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>导入文件夹</DialogTitle>
          <DialogDescription>每张图片路径必须包含“第 N 集”目录；同名资产会按集数独立创建。</DialogDescription>
        </DialogHeader>
        <div className="space-y-3 overflow-y-auto pr-1 text-sm">
          <label className="block text-xs text-muted-foreground">
            资产类型
            <select
              value={type}
              onChange={(event) => { setType(event.target.value); void loadPreview(files, event.target.value); }}
              className="mt-1 block w-full rounded-lg border border-border/50 bg-background px-3 py-2"
            >
              <option value="">请选择</option>
              {TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
          <input ref={inputRef} type="file" multiple accept="image/png,image/jpeg,image/webp,image/gif" className="hidden" onChange={chooseFiles} />
          <button
            type="button"
            disabled={!type || loading}
            onClick={() => inputRef.current && openDirectoryPicker(inputRef.current)}
            className="w-full rounded-lg border border-dashed border-border/60 px-4 py-5 text-muted-foreground hover:bg-muted/40 disabled:cursor-not-allowed disabled:opacity-50"
          >
            选择文件夹（非 Chromium 浏览器可多选图片）
          </button>
          {loading && <div className="flex items-center gap-2 text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />处理中…</div>}
          {error && <p className="text-destructive text-xs">{error}</p>}
          {preview.map((item) => (
            <div key={item.relativePath} className="flex items-center gap-3 rounded-lg border border-border/30 px-3 py-2 text-xs">
              <span className="min-w-0 flex-1 truncate">{item.relativePath}</span>
              <span className={item.reason ? "shrink-0 text-destructive" : "shrink-0 text-muted-foreground"}>
                {item.reason
                  ? item.reason
                  : `第 ${item.episodeNumber} 集 · ${item.kind === "root" ? `创建资产：${item.assetName}` : `添加子资产：${item.variantName}`}`}
              </span>
              <button type="button" onClick={() => removeFile(item.relativePath)} aria-label={`移除 ${item.relativePath}`}><X className="h-3.5 w-3.5" /></button>
            </div>
          ))}
          {results.length > 0 && (
            <div className="space-y-1 rounded-lg bg-muted/40 p-3 text-xs">
              <p>成功 {results.filter((item) => item.status === "success").length}，跳过 {results.filter((item) => item.status === "skipped").length}，失败 {results.filter((item) => item.status === "failed").length}</p>
              {results.filter((item) => item.status !== "success").map((item) => (
                <p key={item.relativePath} className={item.status === "failed" ? "text-destructive" : "text-muted-foreground"}>{item.relativePath}：{item.reason}</p>
              ))}
            </div>
          )}
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className="rounded-lg border px-3 py-2 text-sm">关闭</button>
          <button type="button" disabled={loading || !files.length || !preview.length || hasInvalidPreview} onClick={() => void upload()} className="flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-sm text-primary-foreground disabled:opacity-50"><Upload className="h-4 w-4" />开始导入</button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
