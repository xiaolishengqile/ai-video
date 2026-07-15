package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;

public record PipelineAttempt(
        String runId,
        String conversationId,
        int attemptNumber,
        PipelineResumeType resumeType,
        AiChatReqVO request) {
}
