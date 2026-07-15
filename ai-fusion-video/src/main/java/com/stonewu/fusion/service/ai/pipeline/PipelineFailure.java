package com.stonewu.fusion.service.ai.pipeline;

public record PipelineFailure(
        PipelineFailureCategory category,
        String code,
        String message,
        boolean retryable) {
}
