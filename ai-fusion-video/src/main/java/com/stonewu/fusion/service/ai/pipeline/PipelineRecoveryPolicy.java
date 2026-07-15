package com.stonewu.fusion.service.ai.pipeline;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PipelineRecoveryPolicy {

    private final Duration stallThreshold;

    public PipelineRecoveryPolicy(
            @Value("${ai.pipeline.stall-threshold:PT5M}") Duration stallThreshold) {
        this.stallThreshold = stallThreshold;
    }

    public PipelineRecoveryAction decide(
            PipelineRunStatus status,
            String activeConversationId,
            LocalDateTime lastActivityTime,
            LocalDateTime now) {
        if (status == PipelineRunStatus.WAITING_MANUAL_RESUME
                || status == PipelineRunStatus.FAILED_NON_RETRYABLE
                || status == PipelineRunStatus.CANCELLED) {
            return PipelineRecoveryAction.RESUME;
        }
        if (status != PipelineRunStatus.RUNNING && status != PipelineRunStatus.AUTO_RESUMING) {
            return PipelineRecoveryAction.NONE;
        }
        if (activeConversationId == null) {
            return PipelineRecoveryAction.NONE;
        }
        if (lastActivityTime != null && lastActivityTime.isBefore(now.minus(stallThreshold))) {
            return PipelineRecoveryAction.RECOVER_STALLED;
        }
        return PipelineRecoveryAction.RECONNECT;
    }
}
