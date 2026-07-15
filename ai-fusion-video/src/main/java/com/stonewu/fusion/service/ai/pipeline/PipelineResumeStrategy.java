package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;

import java.util.List;

public interface PipelineResumeStrategy {

    boolean supports(String agentType);

    PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints);
}
