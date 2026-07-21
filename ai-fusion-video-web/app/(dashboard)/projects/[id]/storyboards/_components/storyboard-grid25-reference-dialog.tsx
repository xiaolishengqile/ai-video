"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import { ClipboardPaste, Grid3X3, Image as ImageIcon, Loader2, Plus, Sparkles, X, ZoomIn } from "lucide-react";
import { toast } from "sonner";
import ImageInput from "@/components/dashboard/image-input";
import { SafeImage } from "@/components/ui/safe-image";
import { Textarea } from "@/components/ui/textarea";
import { resolveMediaUrl } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { Project } from "@/lib/api/project";
import type { StoryboardItem, StoryboardWorkflowUpdateReq } from "@/lib/api/storyboard";

type Grid25UpdateHandler = (
  itemId: number,
  data: StoryboardWorkflowUpdateReq
) => Promise<void> | void;

type Grid25GenerateHandler = (
  item: StoryboardItem,
  prompt: string,
  referenceImageUrls: string[]
) => Promise<void> | void;

function parseStringArray(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string" && item.trim()) : [];
  } catch {
    return [];
  }
}

function buildDefaultGrid25Prompt(item: StoryboardItem, project: Project | null | undefined) {
  const style =
    [
      project?.artStyleDescription,
      project?.artStyleImagePrompt,
      project?.artStyle,
  ].find((text) => text && text.trim()) || "高质量精细画面";
  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const duration = Number(item.duration || 15);
  const parts = [
    `请基于我上传的故事板图，做分镜细化扩展。注意：不是把图片切割成25块，而是根据剧情把故事板的原始分镜扩展成连续的细分镜，最终生成一套覆盖该镜头 ${duration} 秒的25宫格完整分镜图，用于生成同样时长的视频。`,
    `项目画风：${style}`,
    `镜头：${shotLabel}`,
    item.shotType ? `景别：${item.shotType}` : null,
    item.content ? `画面内容：${item.content}` : null,
    item.sceneExpectation ? `画面期望：${item.sceneExpectation}` : null,
    item.dialogue ? `对白/旁白：${item.dialogue}` : null,
    item.cameraMovement ? `运镜：${item.cameraMovement}` : null,
    item.cameraAngle ? `机位角度：${item.cameraAngle}` : null,
  ].filter(Boolean);

  if (item.firstFrameImageUrl) {
    parts.push("首帧参考：作为25宫格连续分镜的起始状态。");
  }
  if (item.lastFrameImageUrl) {
    parts.push("尾帧参考：作为25宫格连续分镜的结尾状态。");
  }
  return parts.join("\n");
}

function uniqueUrls(urls: string[]) {
  return Array.from(new Set(urls.map((url) => url.trim()).filter(Boolean)));
}

export function StoryboardGrid25ReferenceDialog({
  open,
  item,
  project,
  onClose,
  onUpdateWorkflow,
  onGenerateGrid25,
}: {
  open: boolean;
  item: StoryboardItem | null;
  project?: Project | null;
  onClose: () => void;
  onUpdateWorkflow?: Grid25UpdateHandler;
  onGenerateGrid25?: Grid25GenerateHandler;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState("");
  const [extraReferenceUrls, setExtraReferenceUrls] = useState<string[]>(() =>
    parseStringArray(item?.grid25ReferenceImageUrls)
  );
  const [includeFirstFrame, setIncludeFirstFrame] = useState(true);
  const [includeLastFrame, setIncludeLastFrame] = useState(true);
  const [prompt, setPrompt] = useState(() =>
    item?.grid25Prompt || (item ? buildDefaultGrid25Prompt(item, project) : "")
  );
  const skipNextChangeConfirmRef = useRef(false);

  const referenceImageUrls = useMemo(() => {
    if (!item) return [];
    return uniqueUrls([
      includeFirstFrame ? item.firstFrameImageUrl || "" : "",
      includeLastFrame ? item.lastFrameImageUrl || "" : "",
      ...extraReferenceUrls,
    ]);
  }, [extraReferenceUrls, includeFirstFrame, includeLastFrame, item]);

  const confirmOverwrite = useCallback(() => {
    if (!item?.grid25ImageUrl) return true;
    return confirm("25宫格图已存在，确认覆盖吗？");
  }, [item?.grid25ImageUrl]);

  if (!open || !item) return null;

  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const duration = Number(item.duration || 0);
  const supportsGrid25 = Number.isInteger(duration) && duration > 0;
  const canGenerate = prompt.trim().length > 0 && supportsGrid25 && !submitting;

  const updateGrid25 = async (data: StoryboardWorkflowUpdateReq) => {
    if (!onUpdateWorkflow) return;
    setUpdating(true);
    try {
      await onUpdateWorkflow(item.id, {
        videoWorkflowMode: "narrative",
        ...data,
      });
    } finally {
      setUpdating(false);
    }
  };

  const handleImageChange = async (nextValue: string) => {
    const nextUrl = nextValue.trim();
    const currentUrl = item.grid25ImageUrl?.trim() || "";
    if (
      nextUrl &&
      currentUrl &&
      nextUrl !== currentUrl &&
      !skipNextChangeConfirmRef.current &&
      !confirmOverwrite()
    ) {
      return;
    }
    skipNextChangeConfirmRef.current = false;
    try {
      // 后端 workflow 接口用非 null 字段表示更新意图，空字符串表示清空
      await updateGrid25({ grid25ImageUrl: nextUrl });
    } catch (err) {
      console.error("更新25宫格图失败:", err);
      alert("更新25宫格图失败，请重试");
    }
  };

  const handleReferenceChange = async (index: number, nextValue: string) => {
    const nextUrls = [...extraReferenceUrls];
    nextUrls[index] = nextValue.trim();
    const normalized = uniqueUrls(nextUrls);
    setExtraReferenceUrls(normalized);
    await updateGrid25({ grid25ReferenceImageUrls: JSON.stringify(normalized) });
  };

  const handleReferenceRemove = async (index: number) => {
    const normalized = extraReferenceUrls.filter((_, i) => i !== index);
    setExtraReferenceUrls(normalized);
    await updateGrid25({ grid25ReferenceImageUrls: JSON.stringify(normalized) });
  };

  const handleGenerate = async () => {
    if (!canGenerate || !onGenerateGrid25) return;
    try {
      setSubmitting(true);
      await updateGrid25({
        grid25Prompt: prompt.trim(),
        grid25ReferenceImageUrls: JSON.stringify(referenceImageUrls),
      });
      await onGenerateGrid25(item, prompt.trim(), referenceImageUrls);
      onClose();
    } catch (err) {
      console.error("提交25宫格生成失败:", err);
      alert("提交25宫格生成失败，请重试");
    } finally {
      setSubmitting(false);
    }
  };

  const handlePastePrompt = async () => {
    if (!navigator.clipboard?.readText) {
      toast.error("当前浏览器不支持读取剪贴板");
      return;
    }
    try {
      const pasted = (await navigator.clipboard.readText()).trim();
      if (!pasted) {
        toast.info("剪贴板里没有可粘贴的提示词");
        return;
      }
      setPrompt(pasted);
      await updateGrid25({ grid25Prompt: pasted });
      toast.success("已粘贴提示词");
    } catch (err) {
      console.error("粘贴25宫格提示词失败:", err);
      toast.error("粘贴失败，请检查浏览器剪贴板权限");
    }
  };

  return (
    <>
      <div className="fixed inset-0 z-[9000] flex items-center justify-center p-4">
        <div
          className="absolute inset-0 bg-black/60 backdrop-blur-sm"
          onClick={() => !submitting && onClose()}
        />
        <div className="relative flex h-[min(860px,calc(100vh-2rem))] max-h-[calc(100vh-2rem)] w-[880px] max-w-[96vw] flex-col overflow-hidden rounded-xl border border-border/30 bg-card shadow-2xl">
          <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border/20 px-5 py-4">
            <div className="min-w-0">
              <h3 className="truncate text-sm font-semibold">
                镜头 #{shotLabel} 25宫格图
              </h3>
              <p className="mt-0.5 text-[10px] text-muted-foreground">
                上传、查看或基于首尾帧与参考图生成剧情25宫格（按镜头真实时长生成）
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="shrink-0 rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-muted disabled:opacity-40"
              title="关闭"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_320px] gap-0 overflow-hidden max-lg:grid-cols-1">
            <div className="min-h-0 overflow-y-auto p-5">
              <div className={cn(updating && "pointer-events-none opacity-70")}>
                <div className="mb-2 flex items-center justify-between gap-2">
                  <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    <Grid3X3 className="h-3 w-3" /> 25宫格图
                  </h4>
                  {item.grid25ImageUrl && (
                    <button
                      type="button"
                      onClick={() => {
                        setPreviewImageUrl(item.grid25ImageUrl);
                        setPreviewImageTitle("25宫格图");
                      }}
                      className="flex h-7 w-7 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground"
                      title="预览25宫格图"
                    >
                      <ZoomIn className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
                <ImageInput
                  value={item.grid25ImageUrl || ""}
                  onChange={(nextUrl) => {
                    void handleImageChange(nextUrl);
                  }}
                  beforeUpload={() => {
                    const ok = confirmOverwrite();
                    skipNextChangeConfirmRef.current = ok;
                    return ok;
                  }}
                  previewHeight="h-[360px]"
                  previewContainerClassName="bg-muted/20"
                  previewImageClassName="object-contain"
                  uploadSubDir="storyboard-grid25"
                  placeholder="粘贴25宫格图链接..."
                  onPreviewClick={
                    item.grid25ImageUrl
                      ? () => {
                          setPreviewImageUrl(item.grid25ImageUrl);
                          setPreviewImageTitle("25宫格图");
                        }
                      : undefined
                  }
                />
              </div>

              <div className="mt-5">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    <Sparkles className="h-3 w-3" /> 生成提示词
                  </h4>
                  <button
                    type="button"
                    onClick={() => void handlePastePrompt()}
                    disabled={updating}
                    className="inline-flex h-7 items-center gap-1 rounded-lg border border-border/30 px-2 text-[10px] font-medium text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
                    title="从剪贴板粘贴提示词"
                  >
                    <ClipboardPaste className="h-3 w-3" />
                    粘贴
                  </button>
                </div>
                <Textarea
                  value={prompt}
                  onChange={(event) => setPrompt(event.target.value)}
                  className="min-h-44 resize-none text-xs leading-relaxed"
                />
              </div>
            </div>

            <aside className="min-h-0 overflow-y-auto border-l border-border/20 p-5 max-lg:border-l-0 max-lg:border-t">
              <div className="mb-4">
                <h4 className="mb-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  <ImageIcon className="h-3 w-3" /> 生成参考图
                </h4>
                <p className="text-[10px] leading-relaxed text-muted-foreground/70">
                  首尾帧会作为25宫格的起止状态参考，额外图片会作为构图、角色或场景参考。
                </p>
              </div>

              {!supportsGrid25 && (
                <p className="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 p-2 text-[10px] leading-relaxed text-amber-700 dark:text-amber-300">
                  当前镜头时长为 {item.duration ?? "未设置"} 秒。请先设置大于 0 秒的镜头时长，再生成25宫格图。
                </p>
              )}

              <div className="space-y-2">
                {item.firstFrameImageUrl && (
                  <label className="flex items-center gap-2 rounded-lg border border-border/20 bg-muted/10 p-2 text-xs">
                    <input
                      type="checkbox"
                      checked={includeFirstFrame}
                      onChange={(event) => setIncludeFirstFrame(event.target.checked)}
                    />
                    <span className="min-w-0 flex-1">参考首帧作为起始状态</span>
                    <button
                      type="button"
                      onClick={() => {
                        setPreviewImageUrl(item.firstFrameImageUrl);
                        setPreviewImageTitle("首帧参考");
                      }}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      <ZoomIn className="h-3.5 w-3.5" />
                    </button>
                  </label>
                )}
                {item.lastFrameImageUrl && (
                  <label className="flex items-center gap-2 rounded-lg border border-border/20 bg-muted/10 p-2 text-xs">
                    <input
                      type="checkbox"
                      checked={includeLastFrame}
                      onChange={(event) => setIncludeLastFrame(event.target.checked)}
                    />
                    <span className="min-w-0 flex-1">参考尾帧作为结尾状态</span>
                    <button
                      type="button"
                      onClick={() => {
                        setPreviewImageUrl(item.lastFrameImageUrl);
                        setPreviewImageTitle("尾帧参考");
                      }}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      <ZoomIn className="h-3.5 w-3.5" />
                    </button>
                  </label>
                )}
              </div>

              <div className="mt-4 space-y-3">
                {extraReferenceUrls.map((url, index) => (
                  <div key={`${index}-${url || "empty"}`} className="rounded-lg border border-border/20 p-2">
                    <div className="mb-1.5 flex items-center justify-between">
                      <span className="text-[10px] font-medium text-muted-foreground">额外参考图 {index + 1}</span>
                      <button
                        type="button"
                        onClick={() => {
                          void handleReferenceRemove(index);
                        }}
                        className="rounded p-1 text-muted-foreground hover:bg-muted/40 hover:text-foreground"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </div>
                    <ImageInput
                      value={url}
                      onChange={(nextUrl) => {
                        void handleReferenceChange(index, nextUrl);
                      }}
                      previewHeight="h-28"
                      previewImageClassName="object-contain"
                      uploadSubDir="storyboard-grid25-refs"
                      placeholder="粘贴参考图链接..."
                      onPreviewClick={
                        url
                          ? () => {
                              setPreviewImageUrl(url);
                              setPreviewImageTitle(`额外参考图 ${index + 1}`);
                            }
                          : undefined
                      }
                    />
                  </div>
                ))}

                <button
                  type="button"
                  onClick={() => setExtraReferenceUrls((prev) => [...prev, ""])}
                  className="flex h-9 w-full items-center justify-center gap-1.5 rounded-lg border border-dashed border-border/40 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:bg-primary/5 hover:text-primary"
                >
                  <Plus className="h-3.5 w-3.5" />
                  添加参考图
                </button>
              </div>
            </aside>
          </div>

          <div className="flex shrink-0 items-center justify-end gap-2 border-t border-border/20 px-5 py-3.5">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="rounded-lg px-4 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted disabled:opacity-40"
            >
              取消
            </button>
            <button
              type="button"
              onClick={handleGenerate}
              disabled={!canGenerate}
              className={cn(
                "flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-xs font-medium text-primary-foreground transition-all hover:bg-primary/90",
                "disabled:pointer-events-none disabled:opacity-40"
              )}
            >
              {submitting ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Sparkles className="h-3.5 w-3.5" />
              )}
              提交生成25宫格
            </button>
          </div>
        </div>
      </div>

      {previewImageUrl && (
        <div
          className="fixed inset-0 z-[10020] flex items-center justify-center bg-black/85 p-4 backdrop-blur-md"
          onClick={() => setPreviewImageUrl(null)}
        >
          <div
            className="relative flex max-h-[90vh] max-w-[90vw] flex-col items-center gap-3"
            onClick={(event) => event.stopPropagation()}
          >
            <button
              onClick={() => setPreviewImageUrl(null)}
              className="absolute -top-12 right-0 rounded-full bg-white/10 p-1.5 text-white transition-colors hover:bg-white/20"
              type="button"
            >
              <X className="h-5 w-5" />
            </button>
            <SafeImage
              src={resolveMediaUrl(previewImageUrl)}
              alt={previewImageTitle}
              fallbackType="image"
              className="max-h-[84vh] max-w-[90vw] rounded-lg object-contain shadow-2xl"
            />
            <p className="text-xs text-white/70">{previewImageTitle}</p>
          </div>
        </div>
      )}
    </>
  );
}
