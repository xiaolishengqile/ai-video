"use client";

import { useState } from "react";
import { X, Lightbulb, Loader2 } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { scriptApi } from "@/lib/api/script";
import { ScriptContentInput } from "@/components/dashboard/script-content-input";

interface StoryToScriptDialogProps {
  open: boolean;
  projectId: number;
  projectName?: string;
  onClose: () => void;
  /** 创建成功后回调，传入剧本信息 */
  onCreated: (script: { id: number; title: string }) => void;
}

export function StoryToScriptDialog({
  open,
  projectId,
  projectName,
  onClose,
  onCreated,
}: StoryToScriptDialogProps) {
  const [title, setTitle] = useState("");
  const [storySynopsis, setStorySynopsis] = useState("");
  const [episodeCount, setEpisodeCount] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async () => {
    const resolvedTitle = title.trim() || projectName?.trim() || "未命名项目";
    const synopsis = storySynopsis.trim();

    if (!synopsis) {
      setError("请描述你的故事想法");
      return;
    }

    const episodes = episodeCount.trim();
    const resolvedSynopsis =
      episodes && /^\d+$/.test(episodes)
        ? `${synopsis}\n\n要求：共创作 ${episodes} 集。`
        : synopsis;

    setLoading(true);
    setError("");
    try {
      const script = await scriptApi.create({
        projectId,
        title: resolvedTitle,
        storySynopsis: resolvedSynopsis,
      });
      setTitle("");
      setStorySynopsis("");
      setEpisodeCount("");
      onCreated({ id: script.id, title: resolvedTitle });
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm"
            onClick={handleClose}
          />
          <motion.div
            initial={{ opacity: 0, scale: 0.95, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 20 }}
            transition={{ duration: 0.2 }}
            className="fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-full max-w-lg"
          >
            <div className="rounded-2xl border border-border/40 p-6 bg-card shadow-2xl shadow-black/20">
              <div className="flex items-center justify-between mb-5">
                <div className="flex items-center gap-2">
                  <Lightbulb className="h-5 w-5 text-amber-400" />
                  <h2 className="text-lg font-semibold">AI 创作剧本</h2>
                </div>
                <button
                  onClick={handleClose}
                  className="p-1.5 rounded-lg hover:bg-muted transition-colors"
                >
                  <X className="h-4 w-4 text-muted-foreground" />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium mb-1.5">
                    剧本标题
                  </label>
                  <input
                    type="text"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    placeholder={
                      projectName?.trim()
                        ? `留空则使用：${projectName.trim()}`
                        : "留空则使用项目名"
                    }
                    className={cn(
                      "w-full px-3.5 py-2.5 rounded-xl text-sm",
                      "bg-muted/50 border border-border/40",
                      "focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/50",
                      "placeholder:text-muted-foreground/50 transition-all"
                    )}
                    autoFocus
                    disabled={loading}
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium mb-1.5">
                    故事种子 <span className="text-destructive">*</span>
                  </label>
                  <ScriptContentInput
                    value={storySynopsis}
                    onChange={setStorySynopsis}
                    placeholder="描述你的故事想法：题材、主角、核心冲突、风格基调……也可上传 .txt / .docx 故事大纲文件。"
                    rows={8}
                    disabled={loading}
                    textareaClassName="focus:ring-amber-500/30 focus:border-amber-500/50"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium mb-1.5">
                    目标集数
                    <span className="ml-1.5 text-xs font-normal text-muted-foreground">
                      选填
                    </span>
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={50}
                    value={episodeCount}
                    onChange={(e) => setEpisodeCount(e.target.value)}
                    placeholder="例如：3（留空则由 AI 根据故事体量决定）"
                    className={cn(
                      "w-full px-3.5 py-2.5 rounded-xl text-sm",
                      "bg-muted/50 border border-border/40",
                      "focus:outline-none focus:ring-2 focus:ring-amber-500/30 focus:border-amber-500/50",
                      "placeholder:text-muted-foreground/50 transition-all"
                    )}
                    disabled={loading}
                  />
                </div>

                {error && <p className="text-sm text-destructive">{error}</p>}
              </div>

              <div className="flex justify-end gap-3 mt-6">
                <button
                  onClick={handleClose}
                  disabled={loading}
                  className="px-4 py-2 rounded-xl text-sm font-medium hover:bg-muted transition-colors disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={loading}
                  className={cn(
                    "flex items-center gap-2 px-5 py-2 rounded-xl text-sm font-medium",
                    "bg-linear-to-r from-amber-500 to-orange-500",
                    "text-white shadow-lg shadow-amber-500/20",
                    "hover:shadow-amber-500/30 active:scale-[0.98] transition-all",
                    "disabled:opacity-50 disabled:cursor-not-allowed"
                  )}
                >
                  {loading ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin" />
                      创建中...
                    </>
                  ) : (
                    <>
                      <Lightbulb className="h-4 w-4" />
                      开始创作
                    </>
                  )}
                </button>
              </div>
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
