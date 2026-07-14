"use client";

import { useEffect, useMemo, useState } from "react";
import { CheckCircle2, Loader2, RefreshCw } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  scriptApi,
  type Script,
  type ScriptAssetBinding,
  type ScriptEpisode,
} from "@/lib/api/script";
import { cn } from "@/lib/utils";

const ASSET_TYPE_LABELS: Record<string, string> = {
  character: "角色",
  scene: "场景",
  prop: "道具",
};

const STATUS_LABELS: Record<string, string> = {
  matched: "已匹配",
  suggested: "建议",
  ambiguous: "待判断",
  unmatched: "未匹配",
  uploaded_unused: "未使用",
};

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: number;
  script: Script;
  episodes: ScriptEpisode[];
  activeEpisodeId: number | null;
}

export function AssetPrebindingDialog({
  open,
  onOpenChange,
  projectId,
  script,
  episodes,
  activeEpisodeId,
}: Props) {
  const [selectedEpisodeId, setSelectedEpisodeId] = useState<number | null>(activeEpisodeId);
  const [bindings, setBindings] = useState<ScriptAssetBinding[]>([]);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [reviewingId, setReviewingId] = useState<number | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setSelectedEpisodeId((prev) => prev ?? activeEpisodeId ?? episodes[0]?.id ?? null);
  }, [activeEpisodeId, episodes, open]);

  const selectedEpisode = episodes.find((episode) => episode.id === selectedEpisodeId) ?? null;

  const loadBindings = async (episodeId: number) => {
    setLoading(true);
    setMessage(null);
    try {
      const data = await scriptApi.listAssetBindings(episodeId);
      setBindings(data);
    } catch (error) {
      console.error("加载资产预匹配失败:", error);
      setMessage(error instanceof Error ? error.message : "加载资产预匹配失败");
      setBindings([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!open || !selectedEpisodeId) return;
    loadBindings(selectedEpisodeId);
  }, [open, selectedEpisodeId]);

  const summary = useMemo(() => {
    return bindings.reduce(
      (acc, item) => {
        acc.total += 1;
        if (item.matchStatus === "matched") acc.matched += 1;
        if (item.matchStatus === "uploaded_unused") acc.uploadedUnused += 1;
        if (item.reviewed) acc.reviewed += 1;
        return acc;
      },
      { total: 0, matched: 0, uploadedUnused: 0, reviewed: 0 }
    );
  }, [bindings]);

  const grouped = useMemo(() => {
    return bindings.reduce<Record<string, ScriptAssetBinding[]>>((acc, item) => {
      const key = item.assetType || "other";
      acc[key] = acc[key] ?? [];
      acc[key].push(item);
      return acc;
    }, {});
  }, [bindings]);

  const handleRun = async () => {
    if (!selectedEpisodeId) return;
    setRunning(true);
    setMessage(null);
    try {
      const result = await scriptApi.runAssetPrebinding({
        projectId,
        scriptId: script.id,
        scriptEpisodeId: selectedEpisodeId,
      });
      setMessage(`预匹配完成：命中 ${result.matched} 个，未使用 ${result.uploadedUnused} 个`);
      await loadBindings(selectedEpisodeId);
    } catch (error) {
      console.error("运行资产预匹配失败:", error);
      setMessage(error instanceof Error ? error.message : "运行资产预匹配失败");
    } finally {
      setRunning(false);
    }
  };

  const handleReview = async (binding: ScriptAssetBinding) => {
    setReviewingId(binding.id);
    setMessage(null);
    try {
      const updated = await scriptApi.reviewAssetBinding(binding.id, {
        assetId: binding.assetId,
        assetItemId: binding.assetItemId,
        matchStatus: binding.assetId ? "matched" : binding.matchStatus,
        matchSource: binding.matchSource ?? "manual_review",
        reviewed: true,
      });
      setBindings((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
    } catch (error) {
      console.error("确认资产匹配失败:", error);
      setMessage(error instanceof Error ? error.message : "确认资产匹配失败");
    } finally {
      setReviewingId(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl max-h-[86vh] overflow-hidden grid-rows-[auto_auto_1fr]">
        <DialogHeader>
          <DialogTitle>资产预匹配检查</DialogTitle>
          <DialogDescription>
            先把当前集已上传资产和剧本文本对应起来；AI 解析场次和后续分镜会优先使用这些结果。
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3 rounded-2xl border border-border/50 bg-muted/20 p-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="h-9 rounded-xl border border-border bg-background px-3 text-sm outline-none"
              value={selectedEpisodeId ?? ""}
              onChange={(event) => setSelectedEpisodeId(Number(event.target.value))}
            >
              {episodes.map((episode) => (
                <option key={episode.id} value={episode.id}>
                  第 {episode.episodeNumber} 集{episode.title ? ` · ${episode.title}` : ""}
                </option>
              ))}
            </select>
            {selectedEpisode && (
              <span className="text-xs text-muted-foreground">
                当前检查第 {selectedEpisode.episodeNumber} 集资产
              </span>
            )}
          </div>
          <Button size="sm" onClick={handleRun} disabled={!selectedEpisodeId || running}>
            {running ? <Loader2 className="animate-spin" /> : <RefreshCw />}
            运行预匹配
          </Button>
        </div>

        <div className="min-h-0 overflow-y-auto space-y-4 pr-1">
          <div className="flex flex-wrap gap-2 text-xs">
            <Badge variant="secondary">总计 {summary.total}</Badge>
            <Badge variant="default">已匹配 {summary.matched}</Badge>
            <Badge variant="outline">已确认 {summary.reviewed}</Badge>
            <Badge variant="ghost">未使用 {summary.uploadedUnused}</Badge>
          </div>

          {message && (
            <div className="rounded-xl border border-border/50 bg-background px-3 py-2 text-xs text-muted-foreground">
              {message}
            </div>
          )}

          {loading ? (
            <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              正在加载预匹配结果…
            </div>
          ) : bindings.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-border py-10 text-center text-sm text-muted-foreground">
              还没有预匹配结果。先点击“运行预匹配”。
            </div>
          ) : (
            Object.entries(grouped).map(([assetType, items]) => (
              <section key={assetType} className="space-y-2">
                <div className="flex items-center gap-2">
                  <h3 className="text-sm font-semibold">{ASSET_TYPE_LABELS[assetType] ?? assetType}</h3>
                  <span className="text-xs text-muted-foreground">{items.length} 个</span>
                </div>
                <div className="space-y-2">
                  {items.map((binding) => (
                    <div
                      key={binding.id}
                      className={cn(
                        "flex items-center justify-between gap-3 rounded-2xl border p-3",
                        binding.reviewed ? "border-emerald-500/20 bg-emerald-500/5" : "border-border/50 bg-background"
                      )}
                    >
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <p className="truncate text-sm font-medium">{binding.entityName || "未命名资产"}</p>
                          <Badge variant={binding.matchStatus === "matched" ? "default" : "outline"}>
                            {STATUS_LABELS[binding.matchStatus] ?? binding.matchStatus}
                          </Badge>
                          {binding.reviewed && (
                            <Badge variant="secondary">
                              <CheckCircle2 />
                              已确认
                            </Badge>
                          )}
                        </div>
                        <p className="mt-1 text-xs text-muted-foreground">
                          assetId: {binding.assetId ?? "—"} · itemId: {binding.assetItemId ?? "—"}
                          {binding.matchSource ? ` · ${binding.matchSource}` : ""}
                        </p>
                      </div>
                      <Button
                        size="xs"
                        variant={binding.reviewed ? "ghost" : "outline"}
                        disabled={binding.reviewed || reviewingId === binding.id}
                        onClick={() => handleReview(binding)}
                      >
                        {reviewingId === binding.id ? <Loader2 className="animate-spin" /> : null}
                        {binding.reviewed ? "已确认" : "确认"}
                      </Button>
                    </div>
                  ))}
                </div>
              </section>
            ))
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
