package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptFullParseResumeStrategyTests {

    @Test
    void partialScenesRemainPendingWhenEpisodeWriterCheckpointFailed() {
        ResumeFixture fixture = resumeFixture();
        when(fixture.scripts().listScenesByEpisode(101L))
                .thenReturn(List.of(ScriptSceneItem.builder().id(1L).build()));
        PipelineCheckpoint failed = checkpoint(
                "episode_scene_writer", "episode_scene_writer:101",
                "{\"scriptEpisodeId\":101}", "{\"status\":\"error\"}");
        failed.setStatus(PipelineCheckpointStatus.FAILED);

        PipelineResumePlan plan = fixture.strategy().buildPlan(fixture.run(), List.of(failed));

        assertThat(plan.pending()).contains("第1集场次");
        assertThat(plan.completed()).doesNotContain("第1集场次");
    }

    @Test
    void scenesAreCompleteOnlyWithSuccessfulWriterCheckpoint() {
        ResumeFixture fixture = resumeFixture();
        when(fixture.scripts().listScenesByEpisode(101L))
                .thenReturn(List.of(ScriptSceneItem.builder().id(1L).build()));
        PipelineCheckpoint succeeded = checkpoint(
                "episode_scene_writer", "episode_scene_writer:101",
                "{\"scriptEpisodeId\":101}",
                "{\"status\":\"success\",\"scriptEpisodeId\":101,\"expectedSceneCount\":1,\"savedSceneCount\":1}");

        PipelineResumePlan plan = fixture.strategy().buildPlan(fixture.run(), List.of(succeeded));

        assertThat(plan.completed()).contains("第1集场次");
        assertThat(plan.pending()).doesNotContain("第1集场次");
    }

    @Test
    void legacySuccessfulWriterWithoutSceneCountProofRemainsPending() {
        ResumeFixture fixture = resumeFixture();
        when(fixture.scripts().listScenesByEpisode(101L))
                .thenReturn(List.of(ScriptSceneItem.builder().id(1L).build()));
        PipelineCheckpoint legacy = checkpoint(
                "episode_scene_writer", "episode_scene_writer:101",
                "{\"scriptEpisodeId\":101}", "{\"status\":\"success\"}");

        PipelineResumePlan plan = fixture.strategy().buildPlan(fixture.run(), List.of(legacy));

        assertThat(plan.pending()).contains("第1集场次");
        assertThat(plan.completed()).doesNotContain("第1集场次");
    }

    @Test
    void resumesScriptFortyFromMissingEpisodesAndStepsOnly() {
        ScriptService scripts = mock(ScriptService.class);
        PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
        ScriptFullParseResumeStrategy strategy = new ScriptFullParseResumeStrategy(scripts, snapshots);
        List<ScriptEpisode> episodes = new ArrayList<>();
        for (int number = 1; number <= 12; number++) {
            episodes.add(ScriptEpisode.builder()
                    .id(100L + number)
                    .scriptId(40L)
                    .episodeNumber(number)
                    .build());
        }
        when(scripts.getById(40L)).thenReturn(Script.builder().id(40L).totalEpisodes(20).build());
        when(scripts.listEpisodes(40L)).thenReturn(episodes);
        when(scripts.listScenesByEpisode(anyLong())).thenReturn(List.of());
        AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_full_parse")
                .setContext(Map.of("scriptId", 40));
        PipelineRun run = PipelineRun.builder()
                .id(11L)
                .agentType("script_full_parse")
                .requestJson(snapshots.serialize(request))
                .build();
        List<PipelineCheckpoint> checkpoints = List.of(
                checkpoint("update_script_info", "update_script_info:40", "{}", "{}"),
                checkpoint("run_script_asset_prebinding", "run_script_asset_prebinding:101",
                        "{\"scriptEpisodeId\":101}", "{\"status\":\"success\"}"));

        PipelineResumePlan plan = strategy.buildPlan(run, checkpoints);

        assertThat(plan.completed()).contains("剧本元信息", "第1-12集", "第1集资产预绑定");
        assertThat(plan.pending()).contains(
                "第13-20集",
                "第2-20集资产预绑定",
                "第1-20集场次",
                "第1-20集快照");
        assertThat(plan.constraints()).contains("不得删除或重新创建第1-12集");
        assertThat(plan.toPromptBlock()).contains("<resume_context>", "<completed>", "<pending>");
    }

    private PipelineCheckpoint checkpoint(String tool, String key, String input, String output) {
        return PipelineCheckpoint.builder()
                .toolName(tool)
                .checkpointKey(key)
                .status(PipelineCheckpointStatus.SUCCEEDED)
                .inputJson(input)
                .outputJson(output)
                .build();
    }

    private ResumeFixture resumeFixture() {
        ScriptService scripts = mock(ScriptService.class);
        PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
        ScriptFullParseResumeStrategy strategy = new ScriptFullParseResumeStrategy(scripts, snapshots);
        when(scripts.getById(40L)).thenReturn(Script.builder().id(40L).totalEpisodes(1).build());
        when(scripts.listEpisodes(40L)).thenReturn(List.of(ScriptEpisode.builder()
                .id(101L).scriptId(40L).episodeNumber(1).build()));
        AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_full_parse")
                .setContext(Map.of("scriptId", 40));
        PipelineRun run = PipelineRun.builder()
                .id(11L)
                .agentType("script_full_parse")
                .requestJson(snapshots.serialize(request))
                .build();
        return new ResumeFixture(scripts, strategy, run);
    }

    private record ResumeFixture(
            ScriptService scripts,
            ScriptFullParseResumeStrategy strategy,
            PipelineRun run) {
    }
}
