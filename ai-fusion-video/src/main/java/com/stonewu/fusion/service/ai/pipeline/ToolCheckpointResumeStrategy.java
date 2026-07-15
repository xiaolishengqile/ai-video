package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolCheckpointResumeStrategy implements PipelineResumeStrategy {

    @Override
    public boolean supports(String agentType) {
        return true;
    }

    @Override
    public PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints) {
        List<String> completed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        for (PipelineCheckpoint checkpoint : checkpoints) {
            String step = checkpoint.getToolName() + "(" + checkpoint.getScopeId() + ")";
            if (checkpoint.getStatus() == PipelineCheckpointStatus.SUCCEEDED) {
                completed.add(step);
            } else if (checkpoint.getReplayPolicy() == CheckpointReplayPolicy.NEVER_REPLAY) {
                constraints.add(step + " 不得自动重放");
            } else {
                pending.add(step);
            }
        }
        return new PipelineResumePlan(completed, pending, constraints);
    }
}
