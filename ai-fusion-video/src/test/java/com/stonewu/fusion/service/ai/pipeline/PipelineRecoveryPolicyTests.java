package com.stonewu.fusion.service.ai.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRecoveryPolicyTests {

    private final PipelineRecoveryPolicy policy = new PipelineRecoveryPolicy(Duration.ofMinutes(5));
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 15, 22, 0);

    @Test
    void cancelledTaskCanResume() {
        assertThat(policy.decide(PipelineRunStatus.CANCELLED, null, null, now))
                .isEqualTo(PipelineRecoveryAction.RESUME);
    }

    @Test
    void activeRecentTaskReconnects() {
        assertThat(policy.decide(PipelineRunStatus.RUNNING, "conversation-1", now.minusMinutes(2), now))
                .isEqualTo(PipelineRecoveryAction.RECONNECT);
    }

    @Test
    void activeInactiveTaskCanBeRecovered() {
        assertThat(policy.decide(PipelineRunStatus.RUNNING, "conversation-1", now.minusMinutes(6), now))
                .isEqualTo(PipelineRecoveryAction.RECOVER_STALLED);
    }

    @Test
    void completedTaskHasNoRecoveryAction() {
        assertThat(policy.decide(PipelineRunStatus.COMPLETED, null, now.minusHours(1), now))
                .isEqualTo(PipelineRecoveryAction.NONE);
    }
}
