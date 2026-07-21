"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ClipboardPaste, Grid3X3, Image as ImageIcon, Loader2, Plus, Sparkles, X, ZoomIn } from "lucide-react";
import { toast } from "sonner";
import ImageInput from "@/components/dashboard/image-input";
import { SafeImage } from "@/components/ui/safe-image";
import { Textarea } from "@/components/ui/textarea";
import { resolveMediaUrl } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { Project } from "@/lib/api/project";
import type { StoryboardItem, StoryboardWorkflowUpdateReq } from "@/lib/api/storyboard";
import type { Asset, AssetItem } from "@/lib/api/asset";

type AssetLookup = Record<number, { item: AssetItem; asset: Asset }>;

type LinkedAssetReference = {
  id: number;
  label: string;
  typeLabel: string;
  imageUrl: string;
};

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

function parseIds(raw: number[] | string | null | undefined): number[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id) => Number.isFinite(Number(id))).map(Number) : [];
  } catch {
    return [];
  }
}

function sceneItemIds(primaryId: number | null | undefined, rawIds: number[] | string | null | undefined) {
  const ids = parseIds(rawIds);
  return primaryId && !ids.includes(primaryId) ? [primaryId, ...ids] : ids;
}

function assetTypeLabel(type: string | null | undefined) {
  if (type === "character") return "角色";
  if (type === "prop") return "道具";
  if (type === "scene") return "场景";
  return "资产";
}

function buildLinkedAssetReferences(item: StoryboardItem | null, assetLookup: AssetLookup): LinkedAssetReference[] {
  if (!item) return [];
  const ids = [
    ...parseIds(item.characterIds),
    ...sceneItemIds(item.sceneAssetItemId, item.sceneAssetItemIds),
    ...parseIds(item.propIds),
  ];
  const seen = new Set<number>();
  return ids.flatMap((id) => {
    if (seen.has(id)) return [];
    seen.add(id);
    const matched = assetLookup[id];
    if (!matched?.item.imageUrl) return [];
    const typeLabel = assetTypeLabel(matched.asset.type);
    const itemName = matched.item.name?.trim();
    const assetName = matched.asset.name?.trim();
    return [{
      id,
      typeLabel,
      label: itemName && itemName !== assetName ? `${itemName} (${assetName})` : assetName || `资产 ${id}`,
      imageUrl: matched.item.imageUrl,
    }];
  });
}

function isActionMode(item: StoryboardItem | null) {
  return item?.videoWorkflowMode === "action" || item?.videoWorkflowResolvedMode === "action";
}

function buildDefaultGridPrompt(
  item: StoryboardItem,
  project: Project | null | undefined,
  actionMode: boolean,
  linkedAssetRefs: LinkedAssetReference[]
) {
  const style =
    [
      project?.artStyleDescription,
      project?.artStyleImagePrompt,
      project?.artStyle,
  ].find((text) => text && text.trim()) || "高质量精细画面";
  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const duration = Number(item.duration || 15);
  const parts = [
    actionMode
      ? `请基于镜头内容、关联资产图和参考图，生成一张 2x2 的4宫格动作故事板。四格连续表现起势、交锋、转折、收束/终势，突出身位变化、动作路线、受力反馈和镜头运动。`
      : `请基于我上传的故事板图，做分镜细化扩展。注意：不是把图片切割成25块，而是根据剧情把故事板的原始分镜扩展成连续的细分镜，最终生成一套覆盖该镜头 ${duration} 秒的25宫格完整分镜图，用于生成同样时长的视频。`,
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
    parts.push(actionMode ? "首帧参考：作为动作起势状态参考。" : "首帧参考：作为25宫格连续分镜的起始状态。");
  }
  if (item.lastFrameImageUrl) {
    parts.push(actionMode ? "尾帧参考：作为动作收束状态参考。" : "尾帧参考：作为25宫格连续分镜的结尾状态。");
  }
  if (linkedAssetRefs.length > 0) {
    parts.push(`关联资产参考：${linkedAssetRefs.map((ref) => `${ref.typeLabel} ${ref.label}`).join("；")}。生成时必须保持角色、道具、场景设定一致。`);
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
  assetLookup = {},
  onClose,
  onUpdateWorkflow,
  onGenerateGrid25,
}: {
  open: boolean;
  item: StoryboardItem | null;
  project?: Project | null;
  assetLookup?: AssetLookup;
  onClose: () => void;
  onUpdateWorkflow?: Grid25UpdateHandler;
  onGenerateGrid25?: Grid25GenerateHandler;
}) {
  const actionMode = isActionMode(item);
  const materialLabel = actionMode ? "4宫格动作故事板" : "25宫格图";
  const imageField = actionMode ? "actionStoryboardImageUrl" : "grid25ImageUrl";
  const promptField = actionMode ? "actionStoryboardPrompt" : "grid25Prompt";
  const currentImageUrl = actionMode ? item?.actionStoryboardImageUrl : item?.grid25ImageUrl;
  const currentPrompt = actionMode ? item?.actionStoryboardPrompt : item?.grid25Prompt;
  const linkedAssetRefs = useMemo(
    () => buildLinkedAssetReferences(item, assetLookup),
    [assetLookup, item]
  );
  const [submitting, setSubmitting] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [previewImageUrl, setPreviewImageUrl] = useState<string | null>(null);
  const [previewImageTitle, setPreviewImageTitle] = useState("");
  const [extraReferenceUrls, setExtraReferenceUrls] = useState<string[]>(() =>
    parseStringArray(item?.grid25ReferenceImageUrls)
  );
  const [includeFirstFrame, setIncludeFirstFrame] = useState(true);
  const [includeLastFrame, setIncludeLastFrame] = useState(true);
  const [selectedLinkedAssetIds, setSelectedLinkedAssetIds] = useState<number[]>(() =>
    buildLinkedAssetReferences(item, assetLookup).map((ref) => ref.id)
  );
  const [prompt, setPrompt] = useState(() =>
    currentPrompt || (item ? buildDefaultGridPrompt(item, project, actionMode, linkedAssetRefs) : "")
  );
  const skipNextChangeConfirmRef = useRef(false);

  useEffect(() => {
    setSelectedLinkedAssetIds((prev) => {
      const linkedIds = linkedAssetRefs.map((ref) => ref.id);
      if (linkedIds.length === 0) return [];
      if (prev.length === 0) return linkedIds;
      return prev.filter((id) => linkedIds.includes(id));
    });
  }, [linkedAssetRefs]);

  const referenceImageUrls = useMemo(() => {
    if (!item) return [];
    const selectedLinkedSet = new Set(selectedLinkedAssetIds);
    return uniqueUrls([
      includeFirstFrame ? item.firstFrameImageUrl || "" : "",
      includeLastFrame ? item.lastFrameImageUrl || "" : "",
      ...linkedAssetRefs
        .filter((ref) => selectedLinkedSet.has(ref.id))
        .map((ref) => ref.imageUrl),
      ...extraReferenceUrls,
    ]);
  }, [extraReferenceUrls, includeFirstFrame, includeLastFrame, item, linkedAssetRefs, selectedLinkedAssetIds]);

  const confirmOverwrite = useCallback(() => {
    if (!currentImageUrl) return true;
    return confirm(`${materialLabel}已存在，确认覆盖吗？`);
  }, [currentImageUrl, materialLabel]);

  if (!open || !item) return null;

  const shotLabel = item.shotNumber || item.autoShotNumber || String(item.id);
  const duration = Number(item.duration || 0);
  const supportsMaterial = Number.isInteger(duration) && duration > 0;
  const canGenerate = prompt.trim().length > 0 && supportsMaterial && !submitting;

  const updateGrid25 = async (data: StoryboardWorkflowUpdateReq) => {
    if (!onUpdateWorkflow) return;
    setUpdating(true);
    try {
      await onUpdateWorkflow(item.id, {
        videoWorkflowMode: actionMode ? "action" : "narrative",
        ...data,
      });
    } finally {
      setUpdating(false);
    }
  };

  const handleImageChange = async (nextValue: string) => {
    const nextUrl = nextValue.trim();
    const currentUrl = currentImageUrl?.trim() || "";
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
      await updateGrid25({ [imageField]: nextUrl });
    } catch (err) {
      console.error(`更新${materialLabel}失败:`, err);
      alert(`更新${materialLabel}失败，请重试`);
    }
  };

  const handleReferenceChange = async (index: number, nextValue: string) => {
    const nextUrls = [...extraReferenceUrls];
    nextUrls[index] = nextValue.trim();
    const normalized = uniqueUrls(nextUrls);
    setExtraReferenceUrls(normalized);
    if (!actionMode) {
      await updateGrid25({ grid25ReferenceImageUrls: JSON.stringify(normalized) });
    }
  };

  const handleReferenceRemove = async (index: number) => {
    const normalized = extraReferenceUrls.filter((_, i) => i !== index);
    setExtraReferenceUrls(normalized);
    if (!actionMode) {
      await updateGrid25({ grid25ReferenceImageUrls: JSON.stringify(normalized) });
    }
  };

  const handleGenerate = async () => {
    if (!canGenerate || !onGenerateGrid25) return;
    try {
      setSubmitting(true);
      await updateGrid25(
        actionMode
          ? { actionStoryboardPrompt: prompt.trim() }
          : {
              grid25Prompt: prompt.trim(),
              grid25ReferenceImageUrls: JSON.stringify(referenceImageUrls),
            }
      );
      await onGenerateGrid25(item, prompt.trim(), referenceImageUrls);
      onClose();
    } catch (err) {
      console.error(`提交${materialLabel}生成失败:`, err);
      alert(`提交${materialLabel}生成失败，请重试`);
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
      await updateGrid25({ [promptField]: pasted });
      toast.success("已粘贴提示词");
    } catch (err) {
      console.error(`粘贴${materialLabel}提示词失败:`, err);
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
                镜头 #{shotLabel} {materialLabel}
              </h3>
              <p className="mt-0.5 text-[10px] text-muted-foreground">
                {actionMode
                  ? "上传、查看或基于关联资产与参考图生成战斗4宫格动作故事板"
                  : "上传、查看或基于首尾帧、关联资产与参考图生成剧情25宫格"}
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
                    <Grid3X3 className="h-3 w-3" /> {materialLabel}
                  </h4>
                  {currentImageUrl && (
                    <button
                      type="button"
                      onClick={() => {
                        setPreviewImageUrl(currentImageUrl);
                        setPreviewImageTitle(materialLabel);
                      }}
                      className="flex h-7 w-7 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground"
                      title={`预览${materialLabel}`}
                    >
                      <ZoomIn className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
                <ImageInput
                  value={currentImageUrl || ""}
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
                  placeholder={`粘贴${materialLabel}链接...`}
                  onPreviewClick={
                    currentImageUrl
                      ? () => {
                          setPreviewImageUrl(currentImageUrl);
                          setPreviewImageTitle(materialLabel);
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
                  首尾帧和关联资产图会自动作为参考；也可以额外添加构图、角色或场景参考图。
                </p>
              </div>

              {!supportsMaterial && (
                <p className="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 p-2 text-[10px] leading-relaxed text-amber-700 dark:text-amber-300">
                  当前镜头时长为 {item.duration ?? "未设置"} 秒。请先设置大于 0 秒的镜头时长，再生成{materialLabel}。
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
                    <span className="min-w-0 flex-1">参考首帧作为{actionMode ? "动作起势" : "起始状态"}</span>
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
                    <span className="min-w-0 flex-1">参考尾帧作为{actionMode ? "动作收束" : "结尾状态"}</span>
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

              {linkedAssetRefs.length > 0 && (
                <div className="mt-4">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <h5 className="text-[10px] font-semibold text-muted-foreground">
                      关联资产图
                    </h5>
                    <span className="text-[10px] text-muted-foreground/50">
                      已选 {selectedLinkedAssetIds.length}/{linkedAssetRefs.length}
                    </span>
                  </div>
                  <div className="space-y-2">
                    {linkedAssetRefs.map((ref) => {
                      const checked = selectedLinkedAssetIds.includes(ref.id);
                      return (
                        <label
                          key={ref.id}
                          className={cn(
                            "flex items-center gap-2 rounded-lg border p-2 text-xs transition-colors",
                            checked
                              ? "border-primary/30 bg-primary/5"
                              : "border-border/20 bg-muted/10"
                          )}
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(event) => {
                              setSelectedLinkedAssetIds((prev) =>
                                event.target.checked
                                  ? Array.from(new Set([...prev, ref.id]))
                                  : prev.filter((id) => id !== ref.id)
                              );
                            }}
                          />
                          <SafeImage
                            src={resolveMediaUrl(ref.imageUrl)}
                            alt={ref.label}
                            fallbackType="image"
                            className="h-9 w-9 shrink-0 rounded-md object-cover"
                          />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate font-medium">{ref.label}</span>
                            <span className="block text-[10px] text-muted-foreground/60">{ref.typeLabel}</span>
                          </span>
                          <button
                            type="button"
                            onClick={(event) => {
                              event.preventDefault();
                              setPreviewImageUrl(ref.imageUrl);
                              setPreviewImageTitle(`${ref.typeLabel}参考：${ref.label}`);
                            }}
                            className="text-muted-foreground hover:text-foreground"
                          >
                            <ZoomIn className="h-3.5 w-3.5" />
                          </button>
                        </label>
                      );
                    })}
                  </div>
                </div>
              )}

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
              提交生成{materialLabel}
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
