package com.stonewu.fusion.service.ai.agentscope;

import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.pipeline.PipelineAttempt;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.AgentMessageService;
import com.stonewu.fusion.service.ai.pipeline.PipelineCheckpointRepository;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumePlan;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumeStrategy;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumeStrategyRegistry;
import com.stonewu.fusion.service.ai.pipeline.PipelineResumeType;
import com.stonewu.fusion.service.ai.pipeline.PipelineRecoveryAction;
import com.stonewu.fusion.service.ai.pipeline.PipelineRecoveryPolicy;
import com.stonewu.fusion.service.ai.pipeline.PipelineRunRepository;
import com.stonewu.fusion.service.ai.pipeline.PipelineRuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentScopePipelineRuntimeTests {

    @Mock
    private PipelineRuntimeService runtime;
    @Mock
    private PipelineRunRepository runs;
    @Mock
    private PipelineCheckpointRepository checkpoints;
    @Mock
    private PipelineResumeStrategyRegistry strategies;
    @Mock
    private AgentScopeAssistantService assistant;
    @Mock
    private AgentConversationService conversations;
    @Mock
    private AgentMessageService messages;

    @Test
    void initialAttemptPublishesRunIdAndCompletesBothLevels() {
        AiChatReqVO request = new AiChatReqVO().setMessage("解析剧本");
        PipelineAttempt attempt = attempt("run-1", "conversation-1", PipelineResumeType.INITIAL, request);
        PipelineRun run = PipelineRun.builder().id(11L).runId("run-1").userId(7L).build();
        when(runtime.startInitial(request, 7L)).thenReturn(attempt);
        when(runs.requireByRunId("run-1")).thenReturn(run);
        when(assistant.stream(eq(request), eq(7L), any(), any()))
                .thenReturn(Flux.just(new AiChatStreamRespVO().setOutputType("DONE")));

        AgentScopePipelineRuntime service = service();

        StepVerifier.create(service.run(request, 7L))
                .assertNext(event -> assertThat(event.getPipelineRunId()).isEqualTo("run-1"))
                .verifyComplete();
        verify(runtime).complete("run-1", "conversation-1");
    }

    @Test
    void attemptLifecycleDoesNotDependOnSseClientSubscription() {
        AiChatReqVO request = new AiChatReqVO().setMessage("解析剧本");
        PipelineAttempt attempt = attempt("run-1", "conversation-1", PipelineResumeType.INITIAL, request);
        PipelineRun run = PipelineRun.builder().id(11L).runId("run-1").userId(7L).build();
        when(runtime.startInitial(request, 7L)).thenReturn(attempt);
        when(runs.requireByRunId("run-1")).thenReturn(run);
        when(assistant.stream(eq(request), eq(7L), any(), any()))
                .thenReturn(Flux.just(new AiChatStreamRespVO().setOutputType("DONE")));

        service().run(request, 7L);

        verify(runtime).complete("run-1", "conversation-1");
    }

    @Test
    void transientFailureExecutesAutoAttemptWithFreshResumeContext() {
        AiChatReqVO initialRequest = new AiChatReqVO().setMessage("解析剧本").setAgentType("script_full_parse");
        AiChatReqVO resumeRequest = new AiChatReqVO().setMessage("解析剧本").setAgentType("script_full_parse");
        PipelineAttempt initial = attempt("run-1", "conversation-1", PipelineResumeType.INITIAL, initialRequest);
        PipelineAttempt resumed = attempt("run-1", "conversation-2", PipelineResumeType.AUTO, resumeRequest);
        PipelineRun run = PipelineRun.builder().id(11L).runId("run-1").userId(7L).agentType("script_full_parse").build();
        PipelineResumeStrategy strategy = mock(PipelineResumeStrategy.class);
        when(runtime.startInitial(initialRequest, 7L)).thenReturn(initial);
        when(runs.requireByRunId("run-1")).thenReturn(run);
        when(runtime.handleFailure(eq("run-1"), eq("conversation-1"), any(TimeoutException.class)))
                .thenReturn(Mono.just(resumed));
        when(checkpoints.listByRunId(11L)).thenReturn(List.of());
        when(strategies.require("script_full_parse")).thenReturn(strategy);
        when(strategy.buildPlan(run, List.of())).thenReturn(
                new PipelineResumePlan(List.of("第 1 集"), List.of("第 2 集"), List.of("不要重建第 1 集")));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Throwable> failure = invocation.getArgument(3);
            failure.accept(new TimeoutException("read timeout"));
            return Flux.just(new AiChatStreamRespVO().setOutputType("ERROR"));
        }).doReturn(Flux.just(new AiChatStreamRespVO().setOutputType("DONE")))
                .when(assistant).stream(any(), eq(7L), any(), any());

        AgentScopePipelineRuntime service = service();

        StepVerifier.create(service.run(initialRequest, 7L)).expectNextCount(2).verifyComplete();
        assertThat(resumeRequest.getContext().get("pipeline_resume").toString())
                .contains("<resume_context>", "第 1 集", "第 2 集", "不要重建第 1 集");
        verify(runtime).complete("run-1", "conversation-2");
    }

    @Test
    void stalledAttemptIsExposedAndCanBeRecovered() {
        AiChatReqVO request = new AiChatReqVO().setMessage("生成分镜").setAgentType("script_to_storyboard");
        PipelineRun run = PipelineRun.builder()
                .id(11L).runId("run-1").userId(7L).agentType("script_to_storyboard")
                .status(com.stonewu.fusion.service.ai.pipeline.PipelineRunStatus.RUNNING)
                .activeConversationId("conversation-1").build();
        PipelineAttempt resumed = attempt("run-1", "conversation-2", PipelineResumeType.MANUAL, request);
        when(runs.requireByRunId("run-1")).thenReturn(run);
        when(messages.latestActivityTime("conversation-1"))
                .thenReturn(LocalDateTime.now().minusMinutes(6));
        when(runtime.startStalledResume("run-1", 7L)).thenReturn(resumed);
        PipelineResumeStrategy strategy = mock(PipelineResumeStrategy.class);
        when(strategies.require("script_to_storyboard")).thenReturn(strategy);
        when(checkpoints.listByRunId(11L)).thenReturn(List.of());
        when(strategy.buildPlan(run, List.of()))
                .thenReturn(new PipelineResumePlan(List.of(), List.of("第1集分镜"), List.of()));
        when(assistant.stream(eq(request), eq(7L), any(), any()))
                .thenReturn(Flux.just(new AiChatStreamRespVO().setOutputType("DONE")));

        AgentScopePipelineRuntime service = service();

        assertThat(service.status("run-1", 7L).getRecoveryAction())
                .isEqualTo(PipelineRecoveryAction.RECOVER_STALLED);
        StepVerifier.create(service.resume("run-1", 7L)).expectNextCount(1).verifyComplete();
        verify(assistant).cancelStream("conversation-1");
        verify(runtime).startStalledResume("run-1", 7L);
    }

    @Test
    void cancelledRunWithStaleActiveConversationStartsNewAttempt() {
        AiChatReqVO request = new AiChatReqVO()
                .setMessage("生成分镜")
                .setAgentType("script_to_storyboard");
        PipelineRun run = PipelineRun.builder()
                .id(11L)
                .runId("run-1")
                .userId(7L)
                .agentType("script_to_storyboard")
                .status(com.stonewu.fusion.service.ai.pipeline.PipelineRunStatus.CANCELLED)
                .activeConversationId("cancelled-conversation")
                .build();
        PipelineAttempt resumed = attempt(
                "run-1", "conversation-2", PipelineResumeType.MANUAL, request);
        PipelineResumeStrategy strategy = mock(PipelineResumeStrategy.class);
        when(runs.requireByRunId("run-1")).thenReturn(run);
        when(runtime.startManualResume("run-1", 7L)).thenReturn(resumed);
        when(strategies.require("script_to_storyboard")).thenReturn(strategy);
        when(checkpoints.listByRunId(11L)).thenReturn(List.of());
        when(strategy.buildPlan(run, List.of()))
                .thenReturn(new PipelineResumePlan(List.of(), List.of("第1集分镜"), List.of()));
        when(assistant.stream(eq(request), eq(7L), any(), any()))
                .thenReturn(Flux.just(new AiChatStreamRespVO().setOutputType("DONE")));
        StepVerifier.create(service().resume("run-1", 7L))
                .expectNextCount(1)
                .verifyComplete();

        verify(runtime).startManualResume("run-1", 7L);
        verify(assistant, never()).reconnectStream("cancelled-conversation");
    }

    private AgentScopePipelineRuntime service() {
        return new AgentScopePipelineRuntime(runtime, runs, checkpoints, strategies, assistant, conversations,
                messages, new PipelineRecoveryPolicy(Duration.ofMinutes(5)));
    }

    private PipelineAttempt attempt(String runId, String conversationId,
            PipelineResumeType resumeType, AiChatReqVO request) {
        return new PipelineAttempt(runId, conversationId,
                resumeType == PipelineResumeType.INITIAL ? 0 : 1, resumeType, request);
    }
}
