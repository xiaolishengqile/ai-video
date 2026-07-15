package com.stonewu.fusion.service.ai.agentscope;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.stonewu.fusion.controller.ai.vo.PipelineStatusRespVO;
import com.stonewu.fusion.entity.ai.AgentConversation;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.pipeline.PipelineAttempt;
import com.stonewu.fusion.service.ai.pipeline.PipelineCheckpointRepository;
import com.stonewu.fusion.service.ai.pipeline.PipelineExecutionContext;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumePlan;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumeStrategyRegistry;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumeType;
import com.stonewu.fusion.service.ai.pipeline.PipelineRunRepository;
import com.stonewu.fusion.service.ai.pipeline.PipelineRunStatus;
import com.stonewu.fusion.service.ai.pipeline.PipelineRuntimeService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 串联同一逻辑 Pipeline 的多次 AgentScope 执行尝试。
 */
@Service
public class AgentScopePipelineRuntime {

    private final PipelineRuntimeService runtime;
    private final PipelineRunRepository runs;
    private final PipelineCheckpointRepository checkpoints;
    private final PipelineResumeStrategyRegistry strategies;
    private final AgentScopeAssistantService assistant;
    private final AgentConversationService conversations;

    public AgentScopePipelineRuntime(
            PipelineRuntimeService runtime,
            PipelineRunRepository runs,
            PipelineCheckpointRepository checkpoints,
            PipelineResumeStrategyRegistry strategies,
            AgentScopeAssistantService assistant,
            AgentConversationService conversations) {
        this.runtime = runtime;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.strategies = strategies;
        this.assistant = assistant;
        this.conversations = conversations;
    }

    public Flux<AiChatStreamRespVO> run(AiChatReqVO request, Long userId) {
        return execute(runtime.startInitial(request, userId), userId);
    }

    public Flux<AiChatStreamRespVO> resume(String runId, Long userId) {
        PipelineRun run = requireOwned(runId, userId);
        if (run.getActiveConversationId() != null) {
            return reconnect(runId, userId);
        }
        return execute(runtime.startManualResume(runId, userId), userId);
    }

    public void cancel(String runId, Long userId) {
        PipelineRun run = requireOwned(runId, userId);
        String conversationId = run.getActiveConversationId();
        if (conversationId != null) {
            assistant.cancelStream(conversationId);
        }
        runtime.cancel(runId, conversationId);
    }

    public PipelineStatusRespVO status(String runId, Long userId) {
        PipelineRun run = requireOwned(runId, userId);
        AgentConversation attempt = conversations.getLatestPipelineAttempt(run.getId());
        return new PipelineStatusRespVO()
                .setRunId(run.getRunId())
                .setStatus(run.getStatus())
                .setActiveConversationId(run.getActiveConversationId())
                .setAttemptNumber(attempt == null ? null : attempt.getAttemptNumber())
                .setResumeType(attempt == null ? null : attempt.getResumeType())
                .setAutoResumeCount(run.getAutoResumeCount())
                .setMaxAutoResume(run.getMaxAutoResume())
                .setErrorCategory(run.getLastErrorCategory())
                .setErrorCode(run.getLastErrorCode())
                .setErrorMessage(run.getLastErrorMessage())
                .setCanResume(run.getStatus() == PipelineRunStatus.WAITING_MANUAL_RESUME
                        || run.getStatus() == PipelineRunStatus.FAILED_NON_RETRYABLE);
    }

    public Flux<AiChatStreamRespVO> reconnect(String runId, Long userId) {
        PipelineRun run = requireOwned(runId, userId);
        if (run.getActiveConversationId() == null) {
            return Flux.empty();
        }
        AgentConversation attempt = conversations.getByConversationId(run.getActiveConversationId());
        return assistant.reconnectStream(run.getActiveConversationId())
                .map(event -> attachRun(event, runId,
                        attempt == null ? null : attempt.getAttemptNumber(),
                        attempt == null ? null : attempt.getResumeType()));
    }

    private Flux<AiChatStreamRespVO> execute(PipelineAttempt attempt, Long userId) {
        PipelineRun run = runs.requireByRunId(attempt.runId());
        AiChatReqVO request = prepareRequest(run, attempt);
        PipelineExecutionContext executionContext = new PipelineExecutionContext(
                run.getId(), run.getRunId(), attempt.conversationId(), attempt.attemptNumber());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();

        Flux<AiChatStreamRespVO> currentAttempt = assistant
                .stream(request, userId, executionContext, failure::set)
                .map(event -> {
                    if ("CANCELLED".equals(event.getOutputType())) {
                        cancelled.set(true);
                    }
                    return attachRun(event, attempt.runId(), attempt.attemptNumber(), attempt.resumeType());
                });

        return currentAttempt.concatWith(Flux.defer(() -> {
            if (cancelled.get()) {
                return Flux.empty();
            }
            Throwable error = failure.get();
            if (error == null) {
                runtime.complete(attempt.runId(), attempt.conversationId());
                return Flux.empty();
            }
            return runtime.handleFailure(attempt.runId(), attempt.conversationId(), error)
                    .flatMapMany(next -> execute(next, userId));
        }));
    }

    private AiChatReqVO prepareRequest(PipelineRun run, PipelineAttempt attempt) {
        AiChatReqVO request = attempt.request();
        request.setConversationId(attempt.conversationId());
        if (attempt.resumeType() != PipelineResumeType.INITIAL) {
            PipelineResumePlan plan = strategies.require(run.getAgentType())
                    .buildPlan(run, checkpoints.listByRunId(run.getId()));
            LinkedHashMap<String, Object> context = new LinkedHashMap<>();
            if (request.getContext() != null) {
                context.putAll(request.getContext());
            }
            context.put("pipeline_resume", plan.toPromptBlock());
            request.setContext(context);
        }
        return request;
    }

    private AiChatStreamRespVO attachRun(AiChatStreamRespVO event, String runId,
            Integer attemptNumber, PipelineResumeType resumeType) {
        return event.setPipelineRunId(runId)
                .setAttemptNumber(attemptNumber)
                .setResumeType(resumeType == null ? null : resumeType.name());
    }

    private PipelineRun requireOwned(String runId, Long userId) {
        PipelineRun run = runs.requireByRunId(runId);
        if (!run.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该 Pipeline 任务");
        }
        return run;
    }
}
