package com.stonewu.fusion.service.ai.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PipelineResumeStrategyRegistry {

    private final List<PipelineResumeStrategy> strategies;
    private final ToolCheckpointResumeStrategy fallback;

    public PipelineResumeStrategyRegistry(
            List<PipelineResumeStrategy> strategies,
            ToolCheckpointResumeStrategy fallback) {
        this.strategies = strategies;
        this.fallback = fallback;
    }

    public PipelineResumeStrategy require(String agentType) {
        return strategies.stream()
                .filter(strategy -> strategy != fallback)
                .filter(strategy -> strategy.supports(agentType))
                .findFirst()
                .orElse(fallback);
    }
}
