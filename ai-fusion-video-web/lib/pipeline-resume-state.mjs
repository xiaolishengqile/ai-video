const STATUS_VIEW = {
  RUNNING: { status: "running", canResume: false },
  AUTO_RESUMING: {
    status: "running",
    canResume: false,
    notice: "模型重试已耗尽，正在从检查点自动续跑 1/1；自动续跑开始",
  },
  WAITING_MANUAL_RESUME: {
    status: "error",
    canResume: true,
    notice: "自动续跑仍失败，等待手动继续",
  },
  FAILED_NON_RETRYABLE: {
    status: "error",
    canResume: true,
    notice: "任务遇到业务错误，请修正后继续",
  },
  COMPLETED: { status: "done", canResume: false },
  CANCELLED: {
    status: "cancelled",
    canResume: true,
    notice: "任务已取消，可从最近检查点继续此任务",
  },
};

export function canManuallyResumeTask(status, pipelineRunId, canResumeFlag = false) {
  return Boolean(pipelineRunId)
    && status !== "done"
    && (canResumeFlag || status === "error" || status === "cancelled");
}

/** 将服务端逻辑任务状态合并进同一张前端任务卡片。 */
export function applyPipelineRunStatus(current, server) {
  const stalledView = {
    status: "running",
    canResume: true,
    notice: "任务长时间无进展，可检测并继续",
  };
  const view = server.recoveryAction === "RECOVER_STALLED"
    ? stalledView
    : STATUS_VIEW[server.status] ?? STATUS_VIEW.RUNNING;
  const timeline = [...(current.timeline ?? [])];
  if (view.notice && timeline.at(-1)?.text !== view.notice) {
    timeline.push({ type: "content", text: view.notice });
  }
  return {
    ...current,
    status: view.status,
    timeline,
    attemptNumber: server.attemptNumber ?? current.attemptNumber,
    resumeType: server.resumeType ?? current.resumeType,
    autoResumeCount: server.autoResumeCount ?? current.autoResumeCount ?? 0,
    canResume: server.recoveryAction === "RECOVER_STALLED"
      ? true
      : server.canResume ?? view.canResume,
    stalled: server.stalled ?? false,
    recoveryAction: server.recoveryAction,
    lastActivityTime: server.lastActivityTime,
    error: server.errorMessage,
  };
}
