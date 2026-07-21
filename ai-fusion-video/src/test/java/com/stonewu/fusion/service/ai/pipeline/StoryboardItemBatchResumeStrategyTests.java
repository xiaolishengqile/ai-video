package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryboardItemBatchResumeStrategyTests {

    private final PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
    private final StoryboardService storyboards = mock(StoryboardService.class);
    private final StoryboardItemBatchResumeStrategy strategy =
            new StoryboardItemBatchResumeStrategy(storyboards, snapshots);

    @Test
    void narrativeExpandResumesOnlyItemsMissingMaterialOrPrompt() {
        when(storyboards.listItems(26L)).thenReturn(List.of(
                item(10L).grid25ImageUrl("/grid-10.png").videoPrompt("prompt 10").build(),
                item(11L).grid25ImageUrl("/grid-11.png").build(),
                item(12L).build()));

        PipelineResumePlan plan = strategy.buildPlan(run("storyboard_narrative_expand"), List.of());

        assertThat(plan.completed()).containsExactly("已完成镜头: 10");
        assertThat(plan.pending()).containsExactly("待处理镜头: 11, 12");
        assertThat(plan.constraints()).contains("只调度待处理镜头，不要重复生成已完成镜头");
    }

    @Test
    void actionExpandResumesOnlyItemsMissingStoryboardMotionPlanOrPrompt() {
        when(storyboards.listItems(26L)).thenReturn(List.of(
                item(10L).actionStoryboardImageUrl("/action-10.png").motionPlan("plan").videoPrompt("prompt").build(),
                item(11L).actionStoryboardImageUrl("/action-11.png").videoPrompt("prompt").build(),
                item(12L).motionPlan("plan").videoPrompt("prompt").build()));

        PipelineResumePlan plan = strategy.buildPlan(run("storyboard_action_expand"), List.of());

        assertThat(plan.completed()).containsExactly("已完成镜头: 10");
        assertThat(plan.pending()).containsExactly("待处理镜头: 11, 12");
    }

    @Test
    void videoPromptGenerationResumesOnlyItemsWithoutVideoPrompt() {
        when(storyboards.listItems(26L)).thenReturn(List.of(
                item(10L).videoPrompt("prompt").build(),
                item(11L).build(),
                item(12L).videoPrompt("prompt").build()));

        PipelineResumePlan plan = strategy.buildPlan(run("storyboard_video_gen"), List.of());

        assertThat(plan.completed()).containsExactly("已完成镜头: 10, 12");
        assertThat(plan.pending()).containsExactly("待处理镜头: 11");
    }

    private PipelineRun run(String agentType) {
        return PipelineRun.builder()
                .agentType(agentType)
                .requestJson(snapshots.serialize(new AiChatReqVO()
                        .setAgentType(agentType)
                        .setContext(Map.of(
                                "storyboardId", 26,
                                "selectedStoryboardItemIds", List.of(10, 11, 12)))))
                .build();
    }

    private StoryboardItem.StoryboardItemBuilder item(Long id) {
        return StoryboardItem.builder().id(id).storyboardId(26L);
    }
}
