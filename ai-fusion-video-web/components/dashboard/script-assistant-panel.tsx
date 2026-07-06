"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";
import {
  Ban,
  Bot,
  Loader2,
  MessageSquarePlus,
  Send,
  Sparkles,
  User,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { MessageTimeline } from "@/components/dashboard/notification-panel/timeline";
import { reducePipelineEvent } from "@/components/dashboard/agent-pipeline/state";
import type { AgentPipelineState } from "@/components/dashboard/agent-pipeline/types";
import {
  cancelPipeline,
  pipelineStream,
  type AiChatStreamEvent,
} from "@/lib/api/ai-pipeline";
import {
  usePipelineStore,
  type InvalidationType,
} from "@/lib/store/pipeline-store";
import { cn } from "@/lib/utils";

const SCRIPT_INVALIDATION_TOOLS = new Set([
  "save_script_episode",
  "save_script_scene_items",
  "update_script",
  "update_script_info",
  "manage_script_scenes",
  "update_script_scene",
  "update_script_scene_item",
  "manage_script_scene_items",
]);

const SUGGESTED_PROMPTS = [
  "帮我查看当前剧本的整体结构",
  "优化当前选中场次的对白，让语气更自然",
  "检查剧本里是否有角色名称不一致的问题",
];

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  text?: string;
  assistantState?: AgentPipelineState;
}

interface ScriptAssistantPanelProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: number;
  scriptId: number;
  scriptTitle?: string;
}

let messageCounter = 0;
function nextMessageId() {
  messageCounter += 1;
  return `msg-${Date.now()}-${messageCounter}`;
}

function bumpInvalidation(type: InvalidationType) {
  usePipelineStore.setState((state) => ({
    invalidation: {
      ...state.invalidation,
      [type]: (state.invalidation[type] || 0) + 1,
    },
  }));
}

function createAssistantState(): AgentPipelineState {
  return {
    status: "running",
    reasoningText: "",
    timeline: [],
  };
}

export function ScriptAssistantPanel({
  open,
  onOpenChange,
  projectId,
  scriptId,
  scriptTitle,
}: ScriptAssistantPanelProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [isRunning, setIsRunning] = useState(false);
  const conversationIdRef = useRef<string | undefined>(undefined);
  const abortRef = useRef<AbortController | null>(null);
  const assistantMessageIdRef = useRef<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = useCallback(() => {
    const container = scrollRef.current;
    if (!container) return;
    container.scrollTop = container.scrollHeight;
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, isRunning, scrollToBottom]);

  const resetConversation = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    conversationIdRef.current = undefined;
    assistantMessageIdRef.current = null;
    setMessages([]);
    setDraft("");
    setIsRunning(false);
  }, []);

  useEffect(() => {
    if (!open) {
      abortRef.current?.abort();
      abortRef.current = null;
      setIsRunning(false);
    }
  }, [open]);

  const handleStreamEvent = useCallback((event: AiChatStreamEvent) => {
    if (event.conversationId) {
      conversationIdRef.current = event.conversationId;
    }

    if (
      event.outputType === "TOOL_FINISHED" &&
      event.toolName
    ) {
      if (SCRIPT_INVALIDATION_TOOLS.has(event.toolName)) {
        bumpInvalidation("scripts");
      }
      if (event.toolName === "create_asset") {
        bumpInvalidation("assets");
      }
    }

    const assistantId = assistantMessageIdRef.current;
    if (!assistantId) return;

    setMessages((prev) =>
      prev.map((message) => {
        if (message.id !== assistantId || message.role !== "assistant") {
          return message;
        }
        const currentState = message.assistantState ?? createAssistantState();
        return {
          ...message,
          assistantState: reducePipelineEvent(currentState, event),
        };
      })
    );
  }, []);

  const sendMessage = useCallback(
    async (rawMessage: string) => {
      const message = rawMessage.trim();
      if (!message || isRunning) return;

      const userMessage: ChatMessage = {
        id: nextMessageId(),
        role: "user",
        text: message,
      };
      const assistantId = nextMessageId();
      assistantMessageIdRef.current = assistantId;

      const assistantMessage: ChatMessage = {
        id: assistantId,
        role: "assistant",
        assistantState: createAssistantState(),
      };

      setMessages((prev) => [...prev, userMessage, assistantMessage]);
      setDraft("");
      setIsRunning(true);

      const request = {
        message,
        conversationId: conversationIdRef.current,
        agentType: "script_assistant",
        category: "assistant",
        title: scriptTitle ? `剧本助手 · ${scriptTitle}` : "剧本助手",
        projectId,
        context: { scriptId },
        autoReferences: [{ type: "script", id: scriptId }],
      };

      abortRef.current?.abort();
      abortRef.current = pipelineStream(request, {
        onEvent: handleStreamEvent,
        onError: (err) => {
          const targetId = assistantMessageIdRef.current;
          if (!targetId) return;
          setMessages((prev) =>
            prev.map((item) => {
              if (item.id !== targetId || item.role !== "assistant") return item;
              const state = item.assistantState ?? createAssistantState();
              return {
                ...item,
                assistantState: {
                  ...state,
                  status: "error",
                  error: err.message,
                },
              };
            })
          );
          setIsRunning(false);
        },
        onComplete: () => {
          const targetId = assistantMessageIdRef.current;
          if (targetId) {
            setMessages((prev) =>
              prev.map((item) => {
                if (item.id !== targetId || item.role !== "assistant") return item;
                const state = item.assistantState ?? createAssistantState();
                if (state.status === "running" || state.status === "reasoning") {
                  return {
                    ...item,
                    assistantState: { ...state, status: "done" },
                  };
                }
                return item;
              })
            );
          }
          setIsRunning(false);
        },
      });
    },
    [handleStreamEvent, isRunning, projectId, scriptId, scriptTitle]
  );

  const handleStop = useCallback(async () => {
    abortRef.current?.abort();
    abortRef.current = null;

    if (conversationIdRef.current) {
      try {
        await cancelPipeline(conversationIdRef.current);
      } catch {
        // ignore cancel errors
      }
    }

    const targetId = assistantMessageIdRef.current;
    if (targetId) {
      setMessages((prev) =>
        prev.map((item) => {
          if (item.id !== targetId || item.role !== "assistant") return item;
          const state = item.assistantState ?? createAssistantState();
          return {
            ...item,
            assistantState: { ...state, status: "cancelled" },
          };
        })
      );
    }

    setIsRunning(false);
  }, []);

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void sendMessage(draft);
    }
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full sm:max-w-md p-0 flex flex-col gap-0 border-l border-border/40"
      >
        <SheetHeader className="px-4 py-3 border-b border-border/20 shrink-0">
          <div className="flex items-center justify-between gap-2 pr-8">
            <SheetTitle className="flex items-center gap-2 text-base">
              <Sparkles className="h-4 w-4 text-violet-400" />
              剧本助手
            </SheetTitle>
            <button
              type="button"
              onClick={resetConversation}
              disabled={isRunning}
              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-colors disabled:opacity-50"
              title="开始新对话"
            >
              <MessageSquarePlus className="h-3.5 w-3.5" />
              新对话
            </button>
          </div>
          <p className="text-xs text-muted-foreground text-left">
            可查看、修改场次和对白，修改后会自动刷新剧本内容
          </p>
        </SheetHeader>

        <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto px-4 py-4 space-y-4">
          {messages.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-center px-2">
              <div className="h-14 w-14 rounded-2xl bg-violet-500/10 flex items-center justify-center mb-4">
                <Bot className="h-7 w-7 text-violet-400/80" />
              </div>
              <p className="text-sm font-medium mb-1">向 AI 描述你想做的修改</p>
              <p className="text-xs text-muted-foreground mb-5 max-w-[260px]">
                例如优化对白、新增场次、调整角色设定等
              </p>
              <div className="w-full space-y-2">
                {SUGGESTED_PROMPTS.map((prompt) => (
                  <button
                    key={prompt}
                    type="button"
                    onClick={() => void sendMessage(prompt)}
                    disabled={isRunning}
                    className={cn(
                      "w-full text-left px-3 py-2.5 rounded-xl text-xs",
                      "border border-border/40 bg-muted/30",
                      "hover:bg-violet-500/5 hover:border-violet-500/30 transition-colors",
                      "disabled:opacity-50"
                    )}
                  >
                    {prompt}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <AnimatePresence initial={false}>
              {messages.map((message) => (
                <motion.div
                  key={message.id}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  className={cn(
                    "flex gap-2",
                    message.role === "user" ? "justify-end" : "justify-start"
                  )}
                >
                  {message.role === "assistant" && (
                    <div className="h-7 w-7 rounded-lg bg-violet-500/10 flex items-center justify-center shrink-0 mt-0.5">
                      <Bot className="h-4 w-4 text-violet-400" />
                    </div>
                  )}
                  <div
                    className={cn(
                      "max-w-[88%] rounded-2xl text-sm",
                      message.role === "user"
                        ? "bg-primary text-primary-foreground px-3.5 py-2.5"
                        : "bg-muted/40 border border-border/30 px-3 py-3 min-w-0 w-full"
                    )}
                  >
                    {message.role === "user" ? (
                      <p className="whitespace-pre-wrap break-words">{message.text}</p>
                    ) : message.assistantState ? (
                      <MessageTimeline
                        reasoningText={message.assistantState.reasoningText}
                        reasoningDurationMs={
                          message.assistantState.reasoningDurationMs
                        }
                        timeline={message.assistantState.timeline}
                        streaming={
                          isRunning &&
                          message.id === assistantMessageIdRef.current &&
                          (message.assistantState.status === "running" ||
                            message.assistantState.status === "reasoning")
                        }
                        error={message.assistantState.error}
                      />
                    ) : null}
                  </div>
                  {message.role === "user" && (
                    <div className="h-7 w-7 rounded-lg bg-primary/10 flex items-center justify-center shrink-0 mt-0.5">
                      <User className="h-4 w-4 text-primary" />
                    </div>
                  )}
                </motion.div>
              ))}
            </AnimatePresence>
          )}
        </div>

        <div className="shrink-0 border-t border-border/20 p-4 space-y-2 bg-card/50">
          <div className="relative">
            <textarea
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="描述你想修改的内容…"
              rows={3}
              disabled={isRunning}
              className={cn(
                "w-full resize-none rounded-xl px-3.5 py-2.5 pr-12 text-sm",
                "bg-muted/50 border border-border/40",
                "focus:outline-none focus:ring-2 focus:ring-violet-500/30 focus:border-violet-500/50",
                "placeholder:text-muted-foreground/50 transition-all",
                "disabled:opacity-60"
              )}
            />
            {isRunning ? (
              <button
                type="button"
                onClick={() => void handleStop()}
                className="absolute right-2 bottom-2 p-2 rounded-lg text-destructive hover:bg-destructive/10 transition-colors"
                title="停止"
              >
                <Ban className="h-4 w-4" />
              </button>
            ) : (
              <button
                type="button"
                onClick={() => void sendMessage(draft)}
                disabled={!draft.trim()}
                className={cn(
                  "absolute right-2 bottom-2 p-2 rounded-lg transition-colors",
                  draft.trim()
                    ? "text-violet-500 hover:bg-violet-500/10"
                    : "text-muted-foreground/40 cursor-not-allowed"
                )}
                title="发送"
              >
                <Send className="h-4 w-4" />
              </button>
            )}
          </div>
          <p className="text-[10px] text-muted-foreground">
            Enter 发送，Shift + Enter 换行
          </p>
        </div>
      </SheetContent>
    </Sheet>
  );
}
