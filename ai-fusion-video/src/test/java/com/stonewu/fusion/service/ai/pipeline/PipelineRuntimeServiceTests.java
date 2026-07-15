package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.AgentConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PipelineRuntimeServiceTests {

    @Test
    void startsAndCompletesInitialAttempt() {
        Fixture fixture = new Fixture();
        PipelineRun run = fixture.run(PipelineRunStatus.RUNNING, 0);
        when(fixture.runs.create(fixture.request, 7L)).thenReturn(run);
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);
        when(fixture.lock.acquire(eq("run-1"), any(String.class)))
                .thenReturn(Optional.of(lease("run-1", "owner-1")));

        PipelineAttempt attempt = fixture.runtime.startInitial(fixture.request, 7L);
        fixture.runtime.complete("run-1", attempt.conversationId());

        assertThat(attempt.attemptNumber()).isZero();
        assertThat(attempt.resumeType()).isEqualTo(PipelineResumeType.INITIAL);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.COMPLETED);
        assertThat(run.getActiveConversationId()).isNull();
        verify(fixture.conversations).createPipelineAttempt(run, attempt);
        verify(fixture.conversations).finish(attempt.conversationId(), "completed");
        verify(fixture.lock).release("run-1", attempt.conversationId());
    }

    @Test
    void transientFailureStartsOneAutomaticResume() {
        Fixture fixture = new Fixture();
        PipelineRun run = fixture.run(PipelineRunStatus.RUNNING, 0);
        run.setActiveConversationId("conversation-0");
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);
        when(fixture.conversations.nextAttemptNumber(11L)).thenReturn(1);
        when(fixture.lock.acquire(eq("run-1"), any(String.class)))
                .thenReturn(Optional.of(lease("run-1", "owner-2")));

        StepVerifier.create(fixture.runtime.handleFailure(
                        "run-1", "conversation-0", new TimeoutException("model timeout")))
                .assertNext(attempt -> {
                    assertThat(attempt.attemptNumber()).isEqualTo(1);
                    assertThat(attempt.resumeType()).isEqualTo(PipelineResumeType.AUTO);
                })
                .verifyComplete();

        assertThat(run.getAutoResumeCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.AUTO_RESUMING);
        verify(fixture.conversations).finish("conversation-0", "failed");
        verify(fixture.conversations).createPipelineAttempt(eq(run), any(PipelineAttempt.class));
        verify(fixture.lock).release("run-1", "conversation-0");
    }

    @Test
    void exhaustedAutomaticResumeWaitsForManualResume() {
        Fixture fixture = new Fixture();
        PipelineRun run = fixture.run(PipelineRunStatus.AUTO_RESUMING, 1);
        run.setActiveConversationId("conversation-1");
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);

        StepVerifier.create(fixture.runtime.handleFailure(
                        "run-1", "conversation-1", new TimeoutException("still unavailable")))
                .verifyComplete();

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.WAITING_MANUAL_RESUME);
        verify(fixture.lock).release("run-1", "conversation-1");
    }

    @Test
    void businessFailureDoesNotAutoResume() {
        Fixture fixture = new Fixture();
        PipelineRun run = fixture.run(PipelineRunStatus.RUNNING, 0);
        run.setActiveConversationId("conversation-0");
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);

        StepVerifier.create(fixture.runtime.handleFailure(
                        "run-1", "conversation-0", new BusinessException("episode invalid")))
                .verifyComplete();

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.FAILED_NON_RETRYABLE);
        assertThat(run.getLastErrorCategory()).isEqualTo(PipelineFailureCategory.BUSINESS_ERROR.name());
        verify(fixture.lock).release("run-1", "conversation-0");
    }

    @Test
    void manuallyResumesOwnedTaskAndCancelledTaskCannotResume() {
        Fixture fixture = new Fixture();
        PipelineRun run = fixture.run(PipelineRunStatus.WAITING_MANUAL_RESUME, 1);
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);
        when(fixture.conversations.nextAttemptNumber(11L)).thenReturn(2);
        when(fixture.lock.acquire(eq("run-1"), any(String.class)))
                .thenReturn(Optional.of(lease("run-1", "owner-3")));

        PipelineAttempt attempt = fixture.runtime.startManualResume("run-1", 7L);
        fixture.runtime.cancel("run-1", attempt.conversationId());

        assertThat(attempt.resumeType()).isEqualTo(PipelineResumeType.MANUAL);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.CANCELLED);
        assertThatThrownBy(() -> fixture.runtime.startManualResume("run-1", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能继续");
    }

    @Test
    void cancellingDuringAutomaticDelayPreventsResume() {
        Fixture fixture = new Fixture(Duration.ofSeconds(5));
        PipelineRun run = fixture.run(PipelineRunStatus.RUNNING, 0);
        run.setActiveConversationId("conversation-0");
        when(fixture.runs.requireByRunId("run-1")).thenReturn(run);

        StepVerifier.withVirtualTime(() -> fixture.runtime.handleFailure(
                        "run-1", "conversation-0", new TimeoutException("model timeout")))
                .then(() -> fixture.runtime.cancel("run-1", null))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();

        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.CANCELLED);
        verify(fixture.conversations, never()).createPipelineAttempt(eq(run), any(PipelineAttempt.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisLockAllowsOnlyOneOwner() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("fv:ai:pipeline:lock:run-1"), any(String.class), any(Duration.class)))
                .thenReturn(true, false);
        PipelineRunLock lock = new PipelineRunLock(redis);

        Optional<PipelineRunLock.Lease> first = lock.acquire("run-1");
        Optional<PipelineRunLock.Lease> second = lock.acquire("run-1");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        verify(values, times(2)).setIfAbsent(
                eq("fv:ai:pipeline:lock:run-1"), any(String.class), any(Duration.class));
    }

    private static PipelineRunLock.Lease lease(String runId, String owner) {
        return new PipelineRunLock.Lease(runId, owner);
    }

    private static final class Fixture {
        private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
        private final PipelineFailureClassifier classifier = new PipelineFailureClassifier();
        private final PipelineRunLock lock = mock(PipelineRunLock.class);
        private final AgentConversationService conversations = mock(AgentConversationService.class);
        private final PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new com.fasterxml.jackson.databind.ObjectMapper());
        private final AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_parser")
                .setProjectId(40L)
                .setMessage("parse script");
        private final PipelineRuntimeService runtime;

        private Fixture() {
            this(Duration.ZERO);
        }

        private Fixture(Duration autoResumeDelay) {
            runtime = new PipelineRuntimeService(
                    runs, classifier, lock, conversations, snapshots, autoResumeDelay);
        }

        private PipelineRun run(PipelineRunStatus status, int autoResumeCount) {
            return PipelineRun.builder()
                    .id(11L)
                    .runId("run-1")
                    .userId(7L)
                    .projectId(40L)
                    .agentType("script_parser")
                    .title("parse script")
                    .requestJson(snapshots.serialize(request))
                    .status(status)
                    .autoResumeCount(autoResumeCount)
                    .maxAutoResume(1)
                    .build();
        }
    }
}
