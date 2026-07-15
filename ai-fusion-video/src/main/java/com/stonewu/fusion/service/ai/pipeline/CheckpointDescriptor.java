package com.stonewu.fusion.service.ai.pipeline;

public record CheckpointDescriptor(
        String checkpointKey,
        String toolName,
        String scopeType,
        String scopeId,
        CheckpointReplayPolicy replayPolicy) {

    public CheckpointDescriptor {
        if (checkpointKey == null || checkpointKey.isBlank()) {
            throw new IllegalArgumentException("checkpointKey 不能为空");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (replayPolicy == null) {
            throw new IllegalArgumentException("replayPolicy 不能为空");
        }
    }
}
