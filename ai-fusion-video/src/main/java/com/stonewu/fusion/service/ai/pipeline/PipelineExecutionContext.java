package com.stonewu.fusion.service.ai.pipeline;

public record PipelineExecutionContext(
        Long pipelineRunId,
        String runId,
        String conversationId,
        int attemptNumber) {
}
