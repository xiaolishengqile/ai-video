"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "framer-motion";
import {
  ArrowLeft,
  Ban,
  CheckCircle2,
  ChevronRight,
  Clock,
  Loader2,
  MessageSquare,
  RotateCcw,
  Trash2,
  X,
  XCircle,
} from "lucide-react";
import {
  deleteConversation,
  listConversations,
  listMessages,
  type AgentConversation,
  type AgentMessage,
} from "@/lib/api/ai-assistant";
import { PIPELINE_AGENT_TYPES } from "@/lib/api/ai-pipeline";
import {
  usePipelineStore,
  type PipelineTask,
} from "@/lib/store/pipeline-store";
import { cn } from "@/lib/utils";
import {
  getAgentTypeName,
  getToolDisplayName,
} from "./constants";
import {
  messagesToTimeline,
  resolveHistoryErrorMessage,
} from "./history";
import { ElapsedText, useSmartScroll } from "./hooks";
import { MessageTimeline } from "./timeline";
import {
  formatDatetime,
  formatElapsed,
  formatTimestamp,
  getElapsedStr,
} from "./utils";

function isTaskCancellable(task: PipelineTask): boolean {
  return task.status === "running" && task.cancellable !== false;
}

const NON_CANCELLABLE_HINT =
  "后台任务执行中，暂不支持停止，可关闭面板稍后查看结果";

function actionButtonClassName(variant: "stop" | "remove" | "delete" = "remove") {
  return cn(
    "p-1.5 rounded-md transition-all shrink-0 disabled:opacity-50",
    variant === "stop"
      ? "text-destructive/70 hover:text-destructive hover:bg-destructive/10"
      : "text-muted-foreground hover:text-destructive hover:bg-destructive/10",
    "opacity-80 hover:opacity-100 focus:opacity-100"
  );
}

function PipelineDetailPanel({ task }: { task: PipelineTask }) {
  const [idleTimelineLength, setIdleTimelineLength] = useState<number | null>(null);
  const timelineLength = task.state.timeline.length;
  const isIdle = task.status === "running" && idleTimelineLength === timelineLength;
  const canCancel = isTaskCancellable(task);
  const isRunning = task.status === "running";
  const timelineRef = useSmartScroll([task.state.timeline, isIdle], isRunning);

  useEffect(() => {
    if (task.status !== "running") return;
    const timer = setTimeout(() => {
      setIdleTimelineLength(timelineLength);
    }, 2000);
    return () => clearTimeout(timer);
  }, [timelineLength, task.status]);

  useEffect(() => {
    if (!task.state.pipelineRunId) return;
    const refresh = () => {
      void usePipelineStore.getState().refreshPipelineStatus(task.id).catch(() => undefined);
    };
    refresh();
    if (task.status !== "running") return;
    const timer = setInterval(refresh, 30_000);
    return () => clearInterval(timer);
  }, [task.id, task.state.pipelineRunId, task.status]);

  const statusText = {
    running: "运行中",
    done: "已完成",
    error: "出错",
    cancelled: "已取消",
  };

  const statusColor = {
    running: "text-blue-400",
    done: "text-green-400",
    error: "text-destructive",
    cancelled: "text-muted-foreground",
  };

  return (
    <div className="flex flex-col h-full">
      <div className="px-4 py-3 border-b border-border/20 shrink-0 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h4 className="text-sm font-semibold truncate">{task.label}</h4>
          <p className={cn("text-xs mt-0.5", statusColor[task.status])}>
            {statusText[task.status]} · <ElapsedText task={task} />
            <span className="text-muted-foreground/50 ml-1">
              启动于 {formatTimestamp(task.createdAt)}
            </span>
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {task.state.canResume && (
            <button
              onClick={() => usePipelineStore.getState().resumePipeline(task.id)}
              className="flex items-center gap-1.5 rounded-lg border border-blue-500/25 bg-blue-500/10 px-2.5 py-1.5 text-[11px] font-medium text-blue-500 transition-colors hover:bg-blue-500/15"
              title={task.state.recoveryAction === "RECOVER_STALLED"
                ? "检测并恢复卡住的任务"
                : "从最近检查点继续"}
            >
              <RotateCcw className="h-3 w-3" />
              {task.state.recoveryAction === "RECOVER_STALLED" ? "检测并继续" : "继续"}
            </button>
          )}
          {canCancel && (
            <button
              onClick={() => usePipelineStore.getState().cancelPipeline(task.id)}
              className="flex items-center gap-1.5 rounded-lg border border-destructive/20 px-2.5 py-1.5 text-[11px] font-medium text-destructive/70 transition-colors hover:border-destructive/40 hover:bg-destructive/10 hover:text-destructive"
              title="停止任务"
            >
              <Ban className="h-3 w-3" />
              停止
            </button>
          )}
          {isRunning && !canCancel && (
            <span
              className="max-w-[9rem] text-right text-[10px] leading-snug text-muted-foreground"
              title={NON_CANCELLABLE_HINT}
            >
              后台执行中
            </span>
          )}
          {!isRunning && (
            <button
              onClick={() => usePipelineStore.getState().removePipeline(task.id)}
              className="flex items-center gap-1.5 rounded-lg border border-border/30 px-2.5 py-1.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              title="删除"
            >
              <Trash2 className="h-3 w-3" />
              删除
            </button>
          )}
        </div>
      </div>

      <div ref={timelineRef} className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3">
        {isRunning && !canCancel && (
          <div className="rounded-lg border border-amber-500/20 bg-amber-500/5 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
            {NON_CANCELLABLE_HINT}
          </div>
        )}
        <MessageTimeline
          reasoningText={task.state.reasoningText}
          reasoningDurationMs={task.state.reasoningDurationMs}
          timeline={task.state.timeline}
          streaming={task.status === "running"}
          error={task.state.error}
        />

        <AnimatePresence>
          {isIdle && task.status === "running" && (
            <motion.div
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.25 }}
              className="flex items-center gap-2 px-3 py-2 rounded-lg bg-primary/5 border border-primary/10"
            >
              <Loader2 className="h-3 w-3 animate-spin text-primary/60" />
              <span className="text-xs text-primary/70 font-medium">AI 全力处理中…</span>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}

function HistoryDetailPanel({
  conversation,
  onDelete,
  deleting,
}: {
  conversation: AgentConversation;
  onDelete?: () => void;
  deleting?: boolean;
}) {
  const [messageState, setMessageState] = useState<{
    conversationId: string;
    messages: AgentMessage[];
  } | null>(null);
  const loading = messageState?.conversationId !== conversation.conversationId;
  const messages =
    messageState?.conversationId === conversation.conversationId
      ? messageState.messages
      : [];

  useEffect(() => {
    let cancelled = false;
    listMessages(conversation.conversationId)
      .then((nextMessages) => {
        if (!cancelled) {
          setMessageState({
            conversationId: conversation.conversationId,
            messages: nextMessages,
          });
        }
      })
      .catch((err) => {
        console.error(err);
        if (!cancelled) {
          setMessageState({
            conversationId: conversation.conversationId,
            messages: [],
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [conversation.conversationId]);

  const isPipeline =
    conversation.category === "task" ||
    conversation.category === "pipeline" ||
    (conversation.agentType != null &&
      (PIPELINE_AGENT_TYPES as readonly string[]).includes(conversation.agentType));
  const displayMessages = isPipeline
    ? messages.filter((message) => message.role !== "user")
    : messages;
  const firstAssistant = displayMessages.find(
    (message) =>
      message.role === "assistant" &&
      !message.parentToolCallId &&
      message.reasoningContent
  );
  const timeline = messagesToTimeline(displayMessages);
  const historyError = resolveHistoryErrorMessage(conversation, displayMessages);

  return (
    <div className="flex flex-col h-full">
      <div className="px-4 py-3 border-b border-border/20 shrink-0 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h4 className="text-sm font-semibold truncate">{conversation.title}</h4>
          <div className="flex items-center gap-2 mt-0.5 flex-wrap">
          {conversation.agentType && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-400 font-medium">
              {getAgentTypeName(conversation.agentType)}
            </span>
          )}
          {conversation.createTime && conversation.lastMessageTime && (() => {
            const duration =
              new Date(conversation.lastMessageTime).getTime() -
              new Date(conversation.createTime).getTime();
            return duration > 0 ? (
              <span className="text-xs text-muted-foreground">
                耗时 {formatElapsed(duration)}
              </span>
            ) : null;
          })()}
          <span className="text-xs text-muted-foreground">
            {formatDatetime(conversation.createTime)}
          </span>
        </div>
        </div>
        {onDelete && (
          <button
            onClick={onDelete}
            disabled={deleting}
            className="shrink-0 flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[11px] font-medium border border-destructive/20 text-destructive/70 hover:bg-destructive/10 hover:text-destructive hover:border-destructive/40 transition-colors disabled:opacity-50"
            title="删除此记录"
          >
            {deleting ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <Trash2 className="h-3 w-3" />
            )}
            删除
          </button>
        )}
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : displayMessages.length === 0 ? (
          <p className="text-sm text-muted-foreground text-center py-8">暂无消息记录</p>
        ) : (
          <MessageTimeline
            reasoningText={firstAssistant?.reasoningContent || undefined}
            reasoningDurationMs={firstAssistant?.reasoningDurationMs || undefined}
            timeline={timeline}
            streaming={false}
            error={historyError}
          />
        )}
      </div>
    </div>
  );
}

function TaskListItem({
  label,
  subtitle,
  icon,
  selected,
  onClick,
  onStop,
  onRemove,
  stopDisabled,
  stopDisabledTitle,
  onDelete,
  deleting,
}: {
  label: string;
  subtitle: ReactNode;
  icon: ReactNode;
  selected: boolean;
  onClick: () => void;
  onStop?: (e: React.MouseEvent) => void;
  onRemove?: (e: React.MouseEvent) => void;
  stopDisabled?: boolean;
  stopDisabledTitle?: string;
  onDelete?: (e: React.MouseEvent) => void;
  deleting?: boolean;
}) {
  return (
    <div
      className={cn(
        "group flex items-center gap-0.5 rounded-lg mb-0.5 transition-colors",
        selected ? "bg-foreground/10" : "hover:bg-foreground/5"
      )}
    >
      <button
        onClick={onClick}
        className="flex-1 flex items-center gap-2 px-2.5 py-2 text-left min-w-0"
      >
        {icon}
        <div className="flex-1 min-w-0">
          <p className="text-xs font-medium truncate">{label}</p>
          <p className="text-[10px] text-muted-foreground truncate">{subtitle}</p>
        </div>
      </button>
      <div className="flex items-center gap-0.5 mr-1 shrink-0">
        {onStop && (
          <button
            onClick={onStop}
            className={actionButtonClassName("stop")}
            title="停止任务"
            aria-label="停止任务"
          >
            <Ban className="h-3 w-3" />
          </button>
        )}
        {stopDisabled && (
          <button
            type="button"
            disabled
            className={cn(actionButtonClassName("stop"), "cursor-not-allowed opacity-40")}
            title={stopDisabledTitle || NON_CANCELLABLE_HINT}
            aria-label={stopDisabledTitle || NON_CANCELLABLE_HINT}
          >
            <Ban className="h-3 w-3" />
          </button>
        )}
        {onRemove && (
          <button
            onClick={onRemove}
            className={actionButtonClassName("delete")}
            title="删除"
            aria-label="删除"
          >
            <Trash2 className="h-3 w-3" />
          </button>
        )}
        {onDelete && (
          <button
            onClick={onDelete}
            disabled={deleting}
            className={actionButtonClassName("delete")}
            title="删除历史记录"
            aria-label="删除历史记录"
          >
            {deleting ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <Trash2 className="h-3 w-3" />
            )}
          </button>
        )}
      </div>
    </div>
  );
}

export function PipelineTaskCard({ task }: { task: PipelineTask }) {
  const { setPanelExpanded, setExpandedTaskId, cancelPipeline, resumePipeline, removePipeline } =
    usePipelineStore();
  const isRunning = task.status === "running";
  const canCancel = isTaskCancellable(task);

  const statusIcon = {
    running: <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-400 shrink-0" />,
    done: <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />,
    error: <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />,
    cancelled: <Ban className="h-3.5 w-3.5 text-muted-foreground shrink-0" />,
  };

  const statusText = {
    running: "运行中",
    done: "已完成",
    error: "出错",
    cancelled: "已取消",
  };

  const getLatestActivity = () => {
    const timeline = task.state.timeline;
    const isTaskStream = task.cancellable === false;
    for (let index = timeline.length - 1; index >= 0; index--) {
      const item = timeline[index];
      if (item.type === "reasoning") {
        return "AI 正在思考…";
      }
      if (item.type === "tool") {
        return item.status === "calling"
          ? `正在${getToolDisplayName(item.name)}…`
          : `${getToolDisplayName(item.name)} 已完成`;
      }
      if (item.type === "content") {
        if (isTaskStream) {
          return task.status === "running" ? "任务执行中…" : "已记录任务结果";
        }
        return task.status === "running" ? "AI 正在输出…" : "已生成回复";
      }
    }

    if (task.state.reasoningText) return "AI 正在思考…";
    return isTaskStream ? "任务准备中…" : "准备中…";
  };

  const handleOpenDetail = () => {
    setExpandedTaskId(task.id);
    setPanelExpanded(true);
  };

  const handleStop = (event: React.MouseEvent) => {
    event.stopPropagation();
    void cancelPipeline(task.id);
  };

  const handleRemove = (event: React.MouseEvent) => {
    event.stopPropagation();
    removePipeline(task.id);
  };

  const handleResume = (event: React.MouseEvent) => {
    event.stopPropagation();
    resumePipeline(task.id);
  };

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8, height: 0 }}
      className={cn(
        "rounded-xl border overflow-hidden transition-colors",
        isRunning
          ? "border-blue-500/20 bg-blue-500/5"
          : task.status === "done"
            ? "border-green-500/20 bg-green-500/5"
            : task.status === "error"
              ? "border-destructive/20 bg-destructive/5"
              : "border-border/20 bg-muted/20"
      )}
    >
      <div className="flex items-center gap-1 px-2 py-2.5 hover:bg-black/5 dark:hover:bg-white/5 transition-colors">
        <button
          type="button"
          onClick={handleOpenDetail}
          className="flex min-w-0 flex-1 items-center gap-2 text-left"
        >
          {statusIcon[task.status]}
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium truncate">{task.label}</p>
            <p className="text-[10px] text-muted-foreground truncate">
              {isRunning ? getLatestActivity() : statusText[task.status]}
              {" · "}
              <ElapsedText task={task} />
            </p>
          </div>
        </button>
        <div className="flex shrink-0 items-center gap-0.5 pr-1">
          {task.state.canResume && (
            <button
              type="button"
              onClick={handleResume}
              className="p-1 text-blue-500 transition-colors hover:text-blue-400"
              title={task.state.recoveryAction === "RECOVER_STALLED"
                ? "检测并恢复卡住的任务"
                : "从检查点继续"}
              aria-label={task.state.recoveryAction === "RECOVER_STALLED"
                ? "检测并恢复卡住的任务"
                : "从检查点继续"}
            >
              <RotateCcw className="h-3 w-3" />
            </button>
          )}
          {canCancel && (
            <button
              type="button"
              onClick={handleStop}
              className={actionButtonClassName("stop")}
              title="停止任务"
              aria-label="停止任务"
            >
              <Ban className="h-3 w-3" />
            </button>
          )}
          {isRunning && !canCancel && (
            <button
              type="button"
              disabled
              className={cn(actionButtonClassName("stop"), "cursor-not-allowed opacity-40")}
              title={NON_CANCELLABLE_HINT}
              aria-label={NON_CANCELLABLE_HINT}
            >
              <Ban className="h-3 w-3" />
            </button>
          )}
          {!isRunning && (
            <button
              type="button"
              onClick={handleRemove}
              className={actionButtonClassName("delete")}
              title="删除"
              aria-label="删除"
            >
              <Trash2 className="h-3 w-3" />
            </button>
          )}
          <button
            type="button"
            onClick={handleOpenDetail}
            className="p-1 text-muted-foreground transition-colors hover:text-foreground"
            title="查看详情"
            aria-label="查看详情"
          >
            <ChevronRight className="h-3 w-3" />
          </button>
        </div>
      </div>
    </motion.div>
  );
}

type SelectedItem =
  | { type: "pipeline"; taskId: string }
  | { type: "history"; conversation: AgentConversation };

function filterHistoryConversations(
  list: AgentConversation[],
  currentConversationIds: Set<string>
) {
  return list.filter(
    (conversation) =>
      conversation.status !== "running" &&
      !currentConversationIds.has(conversation.conversationId)
  );
}

export function ExpandedPanel({ onClose }: { onClose: () => void }) {
  const { tasks, clearCompleted, expandedTaskId, cancelPipeline, removePipeline } =
    usePipelineStore();
  const listEndRef = useRef<HTMLDivElement>(null);
  // mobile: track whether we're showing detail view
  const [mobileShowDetail, setMobileShowDetail] = useState(false);
  // closing animation state
  const [isClosing, setIsClosing] = useState(false);

  const handleClose = useCallback(() => {
    setIsClosing(true);
  }, []);

  const [selected, setSelected] = useState<SelectedItem | null>(() => {
    if (expandedTaskId) {
      const target = tasks.find((task) => task.id === expandedTaskId);
      if (target) return { type: "pipeline", taskId: target.id };
    }
    const running = tasks.find((task) => task.status === "running");
    return running ? { type: "pipeline", taskId: running.id } : null;
  });
  const [conversations, setConversations] = useState<AgentConversation[]>([]);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [deletingHistoryId, setDeletingHistoryId] = useState<number | null>(null);
  const [clearingHistory, setClearingHistory] = useState(false);
  const pageSize = 20;

  const hasMore = conversations.length < historyTotal;

  const loadHistory = useCallback(
    async (page: number, append = false) => {
      setHistoryLoading(true);
      try {
        const result = await listConversations({ pageNo: page, pageSize });
        const currentConversationIds = new Set(
          usePipelineStore
            .getState()
            .tasks.filter((task) => task.state.conversationId)
            .map((task) => task.state.conversationId!)
        );
        const filtered = filterHistoryConversations(result.list, currentConversationIds);

        if (append) {
          setConversations((prev) => [...prev, ...filtered]);
        } else {
          setConversations(filtered);
        }

        setHistoryTotal(result.total - (result.list.length - filtered.length));
        setHistoryPage(page);
      } catch (err) {
        console.error("加载历史对话失败:", err);
      } finally {
        setHistoryLoading(false);
      }
    },
    [pageSize]
  );

  useEffect(() => {
    loadHistory(1);
  }, [loadHistory]);

  const prevTasksRef = useRef(tasks);
  useEffect(() => {
    const prevTasks = prevTasksRef.current;
    prevTasksRef.current = tasks;

    const justFinished = tasks.some((task) => {
      if (task.status === "running") return false;
      const prev = prevTasks.find((previousTask) => previousTask.id === task.id);
      return prev && prev.status === "running";
    });

    if (justFinished) {
      const timer = setTimeout(() => {
        loadHistory(1);
      }, 1500);
      return () => clearTimeout(timer);
    }
  }, [tasks, loadHistory]);

  useEffect(() => {
    if (!listEndRef.current || !hasMore) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !historyLoading && hasMore) {
          loadHistory(historyPage + 1, true);
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(listEndRef.current);
    return () => observer.disconnect();
  }, [hasMore, historyLoading, historyPage, loadHistory]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") handleClose();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const selectedPipelineTask =
    selected?.type === "pipeline"
      ? tasks.find((task) => task.id === selected.taskId)
      : null;
  const selectedConversation =
    selected?.type === "history" ? selected.conversation : null;
  const runningTasks = tasks.filter((task) => task.status === "running");
  const completedTasks = tasks.filter((task) => task.status !== "running");

  // helper: select an item and switch to detail view on mobile
  const handleSelect = (item: SelectedItem) => {
    setSelected(item);
    setMobileShowDetail(true);
  };

  const handleStopPipeline = useCallback(
    (taskId: string, event: React.MouseEvent) => {
      event.stopPropagation();
      void cancelPipeline(taskId);
    },
    [cancelPipeline]
  );

  const handleRemovePipeline = useCallback(
    (taskId: string, event: React.MouseEvent) => {
      event.stopPropagation();
      removePipeline(taskId);
      setSelected((current) =>
        current?.type === "pipeline" && current.taskId === taskId ? null : current
      );
    },
    [removePipeline]
  );

  // helper: go back to list on mobile
  const handleMobileBack = () => {
    setMobileShowDetail(false);
  };

  const handleDeleteHistory = useCallback(
    async (conversation: AgentConversation) => {
      if (
        !confirm(
          `确定删除历史记录「${conversation.title}」吗？删除后不可恢复。`
        )
      ) {
        return;
      }

      setDeletingHistoryId(conversation.id);
      try {
        await deleteConversation(conversation.id);
        setConversations((prev) =>
          prev.filter((item) => item.id !== conversation.id)
        );
        setHistoryTotal((prev) => Math.max(0, prev - 1));
        setSelected((current) =>
          current?.type === "history" &&
          current.conversation.id === conversation.id
            ? null
            : current
        );
        setMobileShowDetail(false);
      } catch (err) {
        console.error("删除历史记录失败:", err);
        alert(err instanceof Error ? err.message : "删除失败，请重试");
      } finally {
        setDeletingHistoryId(null);
      }
    },
    []
  );

  const handleClearHistory = useCallback(async () => {
    if (historyTotal === 0) return;
    if (
      !confirm(
        `确定清空全部 ${historyTotal} 条历史记录吗？删除后不可恢复。`
      )
    ) {
      return;
    }

    setClearingHistory(true);
    try {
      const currentConversationIds = new Set(
        usePipelineStore
          .getState()
          .tasks.filter((task) => task.state.conversationId)
          .map((task) => task.state.conversationId!)
      );

      const idsToDelete: number[] = [];
      let page = 1;
      const fetchPageSize = 50;

      while (true) {
        const result = await listConversations({
          pageNo: page,
          pageSize: fetchPageSize,
        });
        const filtered = filterHistoryConversations(
          result.list,
          currentConversationIds
        );
        idsToDelete.push(...filtered.map((item) => item.id));

        if (page * fetchPageSize >= result.total) {
          break;
        }
        page += 1;
      }

      await Promise.all(idsToDelete.map((id) => deleteConversation(id)));
      setConversations([]);
      setHistoryTotal(0);
      setHistoryPage(1);
      setSelected(null);
      setMobileShowDetail(false);
    } catch (err) {
      console.error("清空历史记录失败:", err);
      alert(err instanceof Error ? err.message : "清空失败，请重试");
      await loadHistory(1);
    } finally {
      setClearingHistory(false);
    }
  }, [historyTotal, loadHistory]);

  /* ---- shared list content (used in both desktop sidebar and mobile full view) ---- */
  const listContent = (
    <>
      {runningTasks.length > 0 && (
        <div className="px-3 pt-3 pb-1">
          <p className="text-[10px] font-medium text-muted-foreground px-1 pb-1.5 uppercase tracking-wider">
            运行中 ({runningTasks.length})
          </p>
          {runningTasks.map((task) => (
            <TaskListItem
              key={task.id}
              label={task.label}
              subtitle={<ElapsedText task={task} />}
              icon={<Loader2 className="h-3.5 w-3.5 animate-spin text-blue-400 shrink-0" />}
              selected={selected?.type === "pipeline" && selected.taskId === task.id}
              onClick={() => handleSelect({ type: "pipeline", taskId: task.id })}
              onStop={
                isTaskCancellable(task)
                  ? (e) => handleStopPipeline(task.id, e)
                  : undefined
              }
              stopDisabled={!isTaskCancellable(task)}
              stopDisabledTitle={NON_CANCELLABLE_HINT}
            />
          ))}
        </div>
      )}

      {completedTasks.length > 0 && (
        <div className="px-3 pt-2 pb-1">
          <p className="text-[10px] font-medium text-muted-foreground px-1 pb-1.5 uppercase tracking-wider">
            当前会话 ({completedTasks.length})
          </p>
          {completedTasks.map((task) => (
            <TaskListItem
              key={task.id}
              label={task.label}
              subtitle={`${
                task.status === "done"
                  ? "已完成"
                  : task.status === "error"
                    ? "出错"
                    : "已取消"
              } · ${getElapsedStr(task)}`}
              icon={
                task.status === "done" ? (
                  <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />
                ) : task.status === "error" ? (
                  <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />
                ) : (
                  <Ban className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                )
              }
              selected={selected?.type === "pipeline" && selected.taskId === task.id}
              onClick={() => handleSelect({ type: "pipeline", taskId: task.id })}
              onRemove={(e) => handleRemovePipeline(task.id, e)}
            />
          ))}
        </div>
      )}

      <div className="px-3 pt-2 pb-3">
        <div className="flex items-center justify-between px-1 pb-1.5">
          <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider flex items-center gap-1">
            <Clock className="h-3 w-3" />
            历史记录
            {historyTotal > 0 ? ` (${historyTotal})` : ""}
          </p>
          {historyTotal > 0 && (
            <button
              onClick={handleClearHistory}
              disabled={clearingHistory || historyLoading}
              className="text-[10px] text-muted-foreground hover:text-destructive transition-colors px-1.5 py-0.5 rounded hover:bg-destructive/10 disabled:opacity-50"
            >
              {clearingHistory ? "清空中…" : "清空"}
            </button>
          )}
        </div>
        {conversations.length === 0 && !historyLoading && (
          <p className="text-xs text-muted-foreground/60 px-1 py-3 text-center">
            暂无历史记录
          </p>
        )}
        {conversations.map((conversation) => {
          const isError =
            conversation.status === "error" ||
            conversation.status === "failed";
          const isDone =
            conversation.status === "completed" ||
            conversation.status === "done";
          const isCancelled = conversation.status === "cancelled";

          return (
            <TaskListItem
              key={conversation.id}
              label={conversation.title}
              subtitle={
                <>
                  {conversation.agentType
                    ? getAgentTypeName(conversation.agentType)
                    : "对话"}
                  {isError
                    ? " · 出错"
                    : isCancelled
                      ? " · 已取消"
                      : isDone
                        ? " · 已完成"
                        : ""}
                  {conversation.createTime && conversation.lastMessageTime && (() => {
                    const duration =
                      new Date(conversation.lastMessageTime).getTime() -
                      new Date(conversation.createTime).getTime();
                    return duration > 0 ? ` · ${formatElapsed(duration)}` : "";
                  })()}
                </>
              }
              icon={
                isError ? (
                  <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />
                ) : isDone ? (
                  <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />
                ) : isCancelled ? (
                  <Ban className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                ) : (
                  <MessageSquare className="h-3.5 w-3.5 text-muted-foreground/50 shrink-0" />
                )
              }
              selected={
                selected?.type === "history" &&
                selected.conversation.id === conversation.id
              }
              onClick={() =>
                handleSelect({ type: "history", conversation })
              }
              onDelete={(e) => {
                e.stopPropagation();
                void handleDeleteHistory(conversation);
              }}
              deleting={deletingHistoryId === conversation.id}
            />
          );
        })}

        <div ref={listEndRef} className="h-1" />
        {historyLoading && (
          <div className="flex items-center justify-center py-3">
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
          </div>
        )}
      </div>
    </>
  );

  /* ---- detail content ---- */
  const detailContent = selectedPipelineTask ? (
    <PipelineDetailPanel task={selectedPipelineTask} />
  ) : selectedConversation ? (
    <HistoryDetailPanel
      conversation={selectedConversation}
      onDelete={() => void handleDeleteHistory(selectedConversation)}
      deleting={deletingHistoryId === selectedConversation.id}
    />
  ) : (
    <div className="flex items-center justify-center h-full text-muted-foreground">
      <div className="text-center">
        <MessageSquare className="h-8 w-8 mx-auto mb-2 opacity-30" />
        <p className="text-sm">选择一个任务查看详情</p>
      </div>
    </div>
  );

  return createPortal(
    <AnimatePresence onExitComplete={onClose}>
      {!isClosing && (
        <>
          <motion.div
            key="expanded-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="fixed inset-0 z-60 bg-black/40 backdrop-blur-sm"
            onClick={handleClose}
          />
          <motion.div
            key="expanded-panel"
            initial={{ opacity: 0, scale: 0.95, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 20 }}
            transition={{ duration: 0.2, ease: "easeOut" }}
            className={cn(
              "fixed z-61 flex flex-col overflow-hidden",
              // mobile: full-screen with safe padding
              "inset-3 rounded-xl",
              // desktop: centered dialog
              "md:inset-auto md:left-1/2 md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2 md:w-[1400px] md:max-w-[92vw] md:h-[75vh] md:rounded-2xl",
              "border border-border/40 bg-card/98 backdrop-blur-xl shadow-2xl shadow-black/30"
            )}
          >
            {/* ---- Header ---- */}
            <div className="flex items-center justify-between px-4 md:px-5 py-3 md:py-3.5 border-b border-border/20 shrink-0">
              <div className="flex items-center gap-2">
                {/* Mobile back button when in detail view */}
                {mobileShowDetail && (
                  <button
                    onClick={handleMobileBack}
                    className="md:hidden p-1 -ml-1 rounded-lg hover:bg-muted transition-colors"
                  >
                    <ArrowLeft className="h-4 w-4 text-muted-foreground" />
                  </button>
                )}
                <h3 className="text-base font-semibold">AI 任务中心</h3>
              </div>
              <div className="flex items-center gap-2">
                {completedTasks.length > 0 && (
                  <button
                    onClick={clearCompleted}
                    className="text-xs text-muted-foreground hover:text-foreground transition-colors px-3 py-1.5 rounded-lg hover:bg-muted hidden md:block"
                    title="删除全部已完成任务"
                  >
                    删除全部已完成
                  </button>
                )}
                <button
                  onClick={handleClose}
                  className="p-1.5 rounded-lg hover:bg-muted transition-colors"
                >
                  <X className="h-4 w-4 text-muted-foreground" />
                </button>
              </div>
            </div>

            {/* ---- Body: desktop two-column, mobile single-column toggle ---- */}
            {/* Desktop layout */}
            <div className="hidden md:flex flex-1 min-h-0">
              <div className="w-72 shrink-0 border-r border-border/20 flex flex-col min-h-0">
                <div className="flex-1 min-h-0 overflow-y-auto">
                  {listContent}
                </div>
              </div>
              <div className="flex-1 min-w-0 min-h-0 bg-muted/10">
                {detailContent}
              </div>
            </div>

            {/* Mobile layout */}
            <div className="flex md:hidden flex-1 min-h-0">
              {mobileShowDetail ? (
                <div className="flex-1 min-w-0 min-h-0 bg-muted/10">
                  {detailContent}
                </div>
              ) : (
                <div className="flex-1 min-h-0 overflow-y-auto">
                  {listContent}
                </div>
              )}
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>,
    document.body
  );
}
