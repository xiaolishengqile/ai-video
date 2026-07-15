package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineResumeIntegrationTests {

    @ParameterizedTest
    @MethodSource("completedSideEffects")
    void completedSideEffectIsNotExecutedAgain(String toolName, String input, String output) {
        PipelineToolCheckpointPolicyRegistry policies = new PipelineToolCheckpointPolicyRegistry();
        CheckpointDescriptor descriptor = policies.describe(toolName, input).orElseThrow();
        PipelineRunRepository runs = mock(PipelineRunRepository.class);
        PipelineCheckpointRepository checkpoints = mock(PipelineCheckpointRepository.class);
        PipelineCheckpoint completed = PipelineCheckpoint.builder()
                .checkpointKey(descriptor.checkpointKey())
                .status(PipelineCheckpointStatus.SUCCEEDED)
                .outputJson(output)
                .build();
        when(checkpoints.find(11L, descriptor.checkpointKey())).thenReturn(completed);
        PipelineToolCheckpointService service = new PipelineToolCheckpointService(
                runs, checkpoints, new PipelineFailureClassifier());

        CheckpointDecision decision = service.beforeExecute(
                new PipelineExecutionContext(11L, "run-1", "conversation-2", 1),
                descriptor,
                input);

        assertThat(decision.action()).isEqualTo(CheckpointDecision.Action.RETURN_STORED);
        assertThat(decision.storedOutput()).isEqualTo(output);
        verify(checkpoints, never()).upsertRunning(anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void businessParameterErrorCannotTriggerAutomaticResume() {
        PipelineFailure failure = new PipelineFailureClassifier()
                .classify(new BusinessException(400, "剧集编号无效"));

        assertThat(failure.category()).isEqualTo(PipelineFailureCategory.BUSINESS_ERROR);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void historicalConversationPreviewBuildsExpectedScriptFortyPlanWithoutModelCall() {
        String historicalConversationId = "f8f3681bf0534a5c8b5f87ee1afc0ec1";
        ScriptService scripts = mock(ScriptService.class);
        PipelineJsonSnapshot snapshots = new PipelineJsonSnapshot(new ObjectMapper());
        AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_full_parse")
                .setContext(Map.of("scriptId", 40));
        PipelineRun run = PipelineRun.builder()
                .id(11L)
                .runId(historicalConversationId)
                .agentType("script_full_parse")
                .requestJson(snapshots.serialize(request))
                .build();
        List<ScriptEpisode> episodes = IntStream.rangeClosed(1, 12)
                .mapToObj(number -> ScriptEpisode.builder()
                        .id(100L + number)
                        .scriptId(40L)
                        .episodeNumber(number)
                        .build())
                .toList();
        when(scripts.getById(40L)).thenReturn(Script.builder().id(40L).totalEpisodes(20).build());
        when(scripts.listEpisodes(40L)).thenReturn(episodes);
        when(scripts.listScenesByEpisode(anyLong())).thenReturn(List.of());
        PipelineCheckpoint prebinding = PipelineCheckpoint.builder()
                .toolName("run_script_asset_prebinding")
                .checkpointKey("run_script_asset_prebinding:101")
                .status(PipelineCheckpointStatus.SUCCEEDED)
                .inputJson("{\"scriptEpisodeId\":101}")
                .outputJson("{\"status\":\"success\"}")
                .build();

        PipelineResumePlan plan = new ScriptFullParseResumeStrategy(scripts, snapshots)
                .buildPlan(run, List.of(prebinding));

        assertThat(plan.completed()).contains("第1-12集", "第1集资产预绑定");
        assertThat(plan.pending()).contains(
                "第13-20集", "第2-20集资产预绑定", "第1-20集场次", "第1-20集快照");
        assertThat(plan.constraints()).contains("不得删除或重新创建第1-12集");
    }

    private static Stream<Arguments> completedSideEffects() {
        return Stream.of(
                Arguments.of(
                        "save_script_episode",
                        "{\"scriptId\":40,\"episodeNumber\":12}",
                        "{\"scriptEpisodeId\":112,\"status\":\"success\"}"),
                Arguments.of(
                        "generate_image",
                        "{\"prompt\":\"cat\"}",
                        "{\"remoteTaskId\":\"image-1\",\"status\":\"success\"}"),
                Arguments.of(
                        "generate_video",
                        "{\"prompt\":\"cat walking\"}",
                        "{\"remoteTaskId\":\"video-1\",\"status\":\"success\"}"));
    }
}
