package com.stonewu.fusion.service.ai.pipeline;

public enum PipelineRunStatus {
    RUNNING,
    AUTO_RESUMING,
    WAITING_MANUAL_RESUME,
    COMPLETED,
    FAILED_NON_RETRYABLE,
    CANCELLED
}
