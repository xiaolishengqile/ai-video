package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.AgentConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
public class PipelineRuntimeService {

    private final PipelineRunRepository runs;
    private final PipelineCheckpointRepository checkpoints;
    private final PipelineFailureClassifier classifier;
    private final PipelineRunLock lock;
    private final AgentConversationService conversations;
    private final PipelineJsonSnapshot snapshots;
    private final Duration autoResumeDelay;

    public PipelineRuntimeService(
            PipelineRunRepository runs,
            PipelineCheckpointRepository checkpoints,
            PipelineFailureClassifier classifier,
            PipelineRunLock lock,
            AgentConversationService conversations,
            PipelineJsonSnapshot snapshots,
            @Value("${ai.pipeline.auto-resume-delay:PT5S}") Duration autoResumeDelay) {
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.classifier = classifier;
        this.lock = lock;
        this.conversations = conversations;
        this.snapshots = snapshots;
        this.autoResumeDelay = autoResumeDelay;
    }

    public PipelineAttempt startInitial(AiChatReqVO request, Long userId) {
        PipelineRun run = runs.create(request, userId);
        return startAttempt(run, request, 0, PipelineResumeType.INITIAL, PipelineRunStatus.RUNNING);
    }

    public Mono<PipelineAttempt> handleFailure(
            String runId,
            String conversationId,
            Throwable error) {
        return Mono.defer(() -> {
            PipelineRun run = requireActiveAttempt(runId, conversationId);
            PipelineFailure failure = classifier.classify(error);
            conversations.finish(conversationId, "failed");
            lock.release(runId, conversationId);
            run.setActiveConversationId(null);
            applyFailure(run, failure);

            if (failure.category() == PipelineFailureCategory.CANCELLED) {
                run.setStatus(PipelineRunStatus.CANCELLED);
                runs.update(run);
                checkpoints.markRunningUnknown(run.getId());
                return Mono.empty();
            }

            if (failure.retryable() && run.getAutoResumeCount() < run.getMaxAutoResume()) {
                run.setAutoResumeCount(run.getAutoResumeCount() + 1);
                run.setStatus(PipelineRunStatus.AUTO_RESUMING);
                runs.update(run);
                return Mono.delay(autoResumeDelay).flatMap(ignored -> {
                    PipelineRun latest = runs.requireByRunId(runId);
                    if (latest.getStatus() == PipelineRunStatus.CANCELLED) {
                        return Mono.empty();
                    }
                    return Mono.just(startResumeAttempt(latest, PipelineResumeType.AUTO));
                });
            }

            run.setStatus(failure.retryable()
                    ? PipelineRunStatus.WAITING_MANUAL_RESUME
                    : PipelineRunStatus.FAILED_NON_RETRYABLE);
            runs.update(run);
            return Mono.empty();
        });
    }

    public PipelineAttempt startManualResume(String runId, Long userId) {
        PipelineRun run = runs.requireByRunId(runId);
        if (!run.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权继续该 Pipeline 任务");
        }
        if (run.getStatus() != PipelineRunStatus.WAITING_MANUAL_RESUME
                && run.getStatus() != PipelineRunStatus.FAILED_NON_RETRYABLE
                && run.getStatus() != PipelineRunStatus.CANCELLED) {
            throw new BusinessException(409, "当前 Pipeline 状态不能继续执行");
        }
        return startResumeAttempt(run, PipelineResumeType.MANUAL);
    }

    public PipelineAttempt startStalledResume(String runId, Long userId) {
        PipelineRun run = runs.requireByRunId(runId);
        if (!run.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权继续该 Pipeline 任务");
        }
        if ((run.getStatus() != PipelineRunStatus.RUNNING
                && run.getStatus() != PipelineRunStatus.AUTO_RESUMING)
                || run.getActiveConversationId() == null) {
            throw new BusinessException(409, "当前 Pipeline 任务不能执行卡住恢复");
        }
        String stalledConversationId = run.getActiveConversationId();
        conversations.finish(stalledConversationId, "stalled");
        lock.release(runId, stalledConversationId);
        run.setActiveConversationId(null);
        run.setStatus(PipelineRunStatus.WAITING_MANUAL_RESUME);
        runs.update(run);
        checkpoints.markRunningUnknown(run.getId());
        return startResumeAttempt(run, PipelineResumeType.MANUAL);
    }

    public void complete(String runId, String conversationId) {
        PipelineRun run = requireActiveAttempt(runId, conversationId);
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setActiveConversationId(null);
        runs.update(run);
        conversations.finish(conversationId, "completed");
        lock.release(runId, conversationId);
    }

    public void cancel(String runId, String conversationId) {
        PipelineRun run = runs.requireByRunId(runId);
        String activeConversationId = run.getActiveConversationId();
        if (activeConversationId != null && !activeConversationId.equals(conversationId)) {
            throw new BusinessException(409, "Pipeline 执行尝试已失效");
        }
        run.setStatus(PipelineRunStatus.CANCELLED);
        run.setActiveConversationId(null);
        runs.update(run);
        checkpoints.markRunningUnknown(run.getId());
        if (activeConversationId != null) {
            conversations.finish(activeConversationId, "cancelled");
            lock.release(runId, activeConversationId);
        }
    }

    private PipelineAttempt startResumeAttempt(PipelineRun run, PipelineResumeType resumeType) {
        AiChatReqVO request = snapshots.deserialize(run.getRequestJson(), AiChatReqVO.class);
        int attemptNumber = conversations.nextAttemptNumber(run.getId());
        PipelineRunStatus status = resumeType == PipelineResumeType.AUTO
                ? PipelineRunStatus.AUTO_RESUMING
                : PipelineRunStatus.RUNNING;
        return startAttempt(run, request, attemptNumber, resumeType, status);
    }

    private PipelineAttempt startAttempt(
            PipelineRun run,
            AiChatReqVO request,
            int attemptNumber,
            PipelineResumeType resumeType,
            PipelineRunStatus status) {
        String conversationId = UUID.randomUUID().toString().replace("-", "");
        lock.acquire(run.getRunId(), conversationId).orElseThrow(
                () -> new BusinessException(409, "Pipeline 任务正在执行"));
        PipelineAttempt attempt = new PipelineAttempt(
                run.getRunId(), conversationId, attemptNumber, resumeType, request);
        run.setStatus(status);
        run.setActiveConversationId(conversationId);
        runs.update(run);
        try {
            conversations.createPipelineAttempt(run, attempt);
            return attempt;
        } catch (RuntimeException error) {
            lock.release(run.getRunId(), conversationId);
            throw error;
        }
    }

    private PipelineRun requireActiveAttempt(String runId, String conversationId) {
        PipelineRun run = runs.requireByRunId(runId);
        if (!conversationId.equals(run.getActiveConversationId())) {
            throw new BusinessException(409, "Pipeline 执行尝试已失效");
        }
        return run;
    }

    private void applyFailure(PipelineRun run, PipelineFailure failure) {
        run.setLastErrorCategory(failure.category().name());
        run.setLastErrorCode(failure.code());
        run.setLastErrorMessage(failure.message());
    }
}
