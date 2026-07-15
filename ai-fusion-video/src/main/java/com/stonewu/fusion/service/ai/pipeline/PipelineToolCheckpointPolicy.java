package com.stonewu.fusion.service.ai.pipeline;

@FunctionalInterface
public interface PipelineToolCheckpointPolicy {

    CheckpointDescriptor describe(String inputJson);
}
