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
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<String, Sinks.Many<AiChatStreamRespVO>> activeStreams =
            new ConcurrentHashMap<>();

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
        PipelineAttempt attempt = runtime.startInitial(request, userId);
        Sinks.Many<AiChatStreamRespVO> sink = newRunSink(attempt.runId());
        execute(attempt, userId, sink);
        return sink.asFlux();
    }

    public Flux<AiChatStreamRespVO> resume(String runId, Long userId) {
        PipelineRun run = requireOwned(runId, userId);
        if (run.getActiveConversationId() != null) {
            return reconnect(runId, userId);
        }
        PipelineAttempt attempt = runtime.startManualResume(runId, userId);
        Sinks.Many<AiChatStreamRespVO> sink = newRunSink(runId);
        execute(attempt, userId, sink);
        return sink.asFlux();
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
        Sinks.Many<AiChatStreamRespVO> activeStream = activeStreams.get(runId);
        if (activeStream != null) {
            return activeStream.asFlux();
        }
        if (run.getActiveConversationId() == null) {
            return Flux.empty();
        }
        AgentConversation attempt = conversations.getByConversationId(run.getActiveConversationId());
        return assistant.reconnectStream(run.getActiveConversationId())
                .map(event -> attachRun(event, runId,
                        attempt == null ? null : attempt.getAttemptNumber(),
                        attempt == null ? null : attempt.getResumeType()));
    }

    private void execute(
            PipelineAttempt attempt,
            Long userId,
            Sinks.Many<AiChatStreamRespVO> sink) {
        PipelineRun run = runs.requireByRunId(attempt.runId());
        AiChatReqVO request = prepareRequest(run, attempt);
        PipelineExecutionContext executionContext = new PipelineExecutionContext(
                run.getId(), run.getRunId(), attempt.conversationId(), attempt.attemptNumber());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        assistant
                .stream(request, userId, executionContext, failure::set)
                .map(event -> attachRun(
                        event, attempt.runId(), attempt.attemptNumber(), attempt.resumeType()))
                .subscribe(
                        event -> handleAttemptEvent(attempt, userId, sink, failure, event),
                        error -> resumeAfterFailure(attempt, userId, sink, error));
    }

    private void handleAttemptEvent(
            PipelineAttempt attempt,
            Long userId,
            Sinks.Many<AiChatStreamRespVO> sink,
            AtomicReference<Throwable> failure,
            AiChatStreamRespVO event) {
        sink.tryEmitNext(event);
        if (event.getParentToolCallId() != null || event.getAgentName() != null) {
            return;
        }
        switch (event.getOutputType()) {
            case "DONE" -> {
                runtime.complete(attempt.runId(), attempt.conversationId());
                finishStream(attempt.runId(), sink);
            }
            case "ERROR" -> resumeAfterFailure(
                    attempt,
                    userId,
                    sink,
                    failure.get() != null
                            ? failure.get()
                            : new IllegalStateException(event.getError()));
            case "CANCELLED" -> finishStream(attempt.runId(), sink);
            default -> {
                // 非终态事件只转发。
            }
        }
    }

    private void resumeAfterFailure(
            PipelineAttempt attempt,
            Long userId,
            Sinks.Many<AiChatStreamRespVO> sink,
            Throwable failure) {
        runtime.handleFailure(attempt.runId(), attempt.conversationId(), failure)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .subscribe(
                        next -> {
                            if (next.isPresent()) {
                                execute(next.get(), userId, sink);
                            } else {
                                finishStream(attempt.runId(), sink);
                            }
                        },
                        error -> {
                            sink.tryEmitError(error);
                            activeStreams.remove(attempt.runId(), sink);
                        });
    }

    private Sinks.Many<AiChatStreamRespVO> newRunSink(String runId) {
        Sinks.Many<AiChatStreamRespVO> sink = Sinks.many().replay().limit(2048);
        Sinks.Many<AiChatStreamRespVO> existing = activeStreams.putIfAbsent(runId, sink);
        if (existing != null) {
            return existing;
        }
        return sink;
    }

    private void finishStream(String runId, Sinks.Many<AiChatStreamRespVO> sink) {
        sink.tryEmitComplete();
        activeStreams.remove(runId, sink);
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
