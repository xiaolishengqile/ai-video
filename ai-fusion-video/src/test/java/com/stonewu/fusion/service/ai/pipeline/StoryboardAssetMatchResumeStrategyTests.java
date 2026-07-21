package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardAssetMatchResumeStrategyTests {

    private final PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());

    @Test
    void resumesOnlyUnmatchedStoryboardItemsAndTreatsSuccessfulWriteBackAsDone() {
        PipelineRun run = PipelineRun.builder()
                .agentType("storyboard_asset_matcher")
                .requestJson(snapshots.serialize(new AiChatReqVO()
                        .setAgentType("storyboard_asset_matcher")
                        .setContext(Map.of(
                                "storyboardId", 26,
                                "selectedStoryboardItemIds", List.of(10, 11, 12, 13)))))
                .build();

        PipelineResumePlan plan = new StoryboardAssetMatchResumeStrategy(snapshots)
                .buildPlan(run, List.of(
                        checkpoint("match_storyboard_item_assets", matchInput(10), "ok", PipelineCheckpointStatus.SUCCEEDED),
                        checkpoint("match_storyboard_item_assets", matchInput(11), null, PipelineCheckpointStatus.FAILED),
                        checkpoint("match_storyboard_item_assets", matchInput(12), null, PipelineCheckpointStatus.FAILED),
                        checkpoint("update_storyboard_item_assets", "{\"storyboardItemId\":12}", "{\"status\":\"success\"}",
                                PipelineCheckpointStatus.SUCCEEDED)));

        assertThat(plan.completed()).containsExactly("已匹配镜头: 10, 12");
        assertThat(plan.pending()).containsExactly("待匹配镜头: 11, 13");
        assertThat(plan.constraints()).contains("只调度待匹配镜头，不要重复处理已匹配镜头");
        assertThat(plan.toPromptBlock()).contains("待匹配镜头: 11, 13");
    }

    private PipelineCheckpoint checkpoint(
            String toolName,
            String inputJson,
            String outputJson,
            PipelineCheckpointStatus status) {
        return PipelineCheckpoint.builder()
                .toolName(toolName)
                .status(status)
                .inputJson(inputJson)
                .outputJson(outputJson)
                .build();
    }

    private String matchInput(long storyboardItemId) {
        return "{\"message\":\"请为分镜镜头匹配当前集资产。\\nstoryboardItemId: "
                + storyboardItemId + "\\nprojectId: 18\"}";
    }
}
