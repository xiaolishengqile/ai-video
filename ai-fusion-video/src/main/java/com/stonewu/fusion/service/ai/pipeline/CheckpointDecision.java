package com.stonewu.fusion.service.ai.pipeline;

public record CheckpointDecision(Action action, String storedOutput, String message) {

    public static CheckpointDecision execute() {
        return new CheckpointDecision(Action.EXECUTE, null, null);
    }

    public static CheckpointDecision returnStored(String output) {
        return new CheckpointDecision(Action.RETURN_STORED, output, null);
    }

    public static CheckpointDecision requireManual(String message) {
        return new CheckpointDecision(Action.REQUIRE_MANUAL, null, message);
    }

    public enum Action {
        EXECUTE,
        RETURN_STORED,
        REQUIRE_MANUAL
    }
}
