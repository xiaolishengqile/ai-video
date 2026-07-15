package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptToStoryboardResumeStrategyTests {

    @Test
    void resumesOnlyEpisodesWithoutVerifiedStoryboardOutput() {
        ScriptService scripts = mock(ScriptService.class);
        StoryboardService storyboards = mock(StoryboardService.class);
        PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
        PipelineRun run = run(snapshots);
        ScriptEpisode first = ScriptEpisode.builder().id(220L).episodeNumber(1).build();
        ScriptEpisode second = ScriptEpisode.builder().id(221L).episodeNumber(2).build();
        StoryboardEpisode firstStoryboard = StoryboardEpisode.builder()
                .id(700L).storyboardId(77L).scriptEpisodeId(220L).episodeNumber(1).build();
        when(scripts.listEpisodes(42L)).thenReturn(List.of(first, second));
        when(storyboards.getEpisodeByScriptEpisode(77L, 220L)).thenReturn(firstStoryboard);
        when(storyboards.getEpisodeByScriptEpisode(77L, 221L)).thenReturn(null);
        when(storyboards.listScenesByEpisode(700L)).thenReturn(List.of(
                StoryboardScene.builder().id(701L).episodeId(700L).build(),
                StoryboardScene.builder().id(702L).episodeId(700L).build()));
        when(storyboards.listItems(77L)).thenReturn(List.of(
                item(1L, 700L), item(2L, 700L), item(3L, 700L), item(4L, 700L)));

        PipelineResumePlan plan = new ScriptToStoryboardResumeStrategy(scripts, storyboards, snapshots)
                .buildPlan(run, List.of(
                        checkpoint("storyboard_asset_preprocessor", "{}", "{\"status\":\"success\"}"),
                        checkpoint("create_project_asset_catalog_snapshot",
                                "{\"scriptEpisodeId\":220}", "{\"snapshotId\":1}"),
                        checkpoint("episode_storyboard_writer",
                                "{\"scriptEpisodeId\":220,\"assetCatalogSnapshotId\":1}",
                                "{\"status\":\"success\",\"scriptEpisodeId\":220,\"sceneCount\":2,\"shotCount\":4}")));

        assertThat(plan.completed()).contains("分镜资产预处理", "第1集资产快照", "第1集分镜");
        assertThat(plan.pending()).contains("第2集资产快照", "第2集分镜");
        assertThat(plan.constraints()).contains("不得重新生成第1集分镜");
    }

    @Test
    void treatsMismatchedDatabaseCountsAsPending() {
        ScriptService scripts = mock(ScriptService.class);
        StoryboardService storyboards = mock(StoryboardService.class);
        PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
        PipelineRun run = run(snapshots);
        ScriptEpisode episode = ScriptEpisode.builder().id(220L).episodeNumber(1).build();
        StoryboardEpisode storyboardEpisode = StoryboardEpisode.builder()
                .id(700L).storyboardId(77L).scriptEpisodeId(220L).episodeNumber(1).build();
        when(scripts.listEpisodes(42L)).thenReturn(List.of(episode));
        when(storyboards.getEpisodeByScriptEpisode(77L, 220L)).thenReturn(storyboardEpisode);
        when(storyboards.listScenesByEpisode(700L)).thenReturn(List.of(
                StoryboardScene.builder().id(701L).episodeId(700L).build()));
        when(storyboards.listItems(77L)).thenReturn(List.of(item(1L, 700L)));

        PipelineResumePlan plan = new ScriptToStoryboardResumeStrategy(scripts, storyboards, snapshots)
                .buildPlan(run, List.of(checkpoint("episode_storyboard_writer",
                        "{\"scriptEpisodeId\":220,\"assetCatalogSnapshotId\":1}",
                        "{\"status\":\"success\",\"scriptEpisodeId\":220,\"sceneCount\":2,\"shotCount\":4}")));

        assertThat(plan.completed()).doesNotContain("第1集分镜");
        assertThat(plan.pending()).contains("第1集分镜");
    }

    private PipelineRun run(PipelineJsonSnapshot snapshots) {
        AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_to_storyboard")
                .setContext(Map.of("scriptId", 42, "storyboardId", 77));
        return PipelineRun.builder()
                .agentType("script_to_storyboard")
                .requestJson(snapshots.serialize(request))
                .build();
    }

    private PipelineCheckpoint checkpoint(String toolName, String input, String output) {
        return PipelineCheckpoint.builder()
                .toolName(toolName)
                .status(PipelineCheckpointStatus.SUCCEEDED)
                .inputJson(input)
                .outputJson(output)
                .build();
    }

    private StoryboardItem item(Long id, Long episodeId) {
        return StoryboardItem.builder().id(id).storyboardEpisodeId(episodeId).build();
    }
}
