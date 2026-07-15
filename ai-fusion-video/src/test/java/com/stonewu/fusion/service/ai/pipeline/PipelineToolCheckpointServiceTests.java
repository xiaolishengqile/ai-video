package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineToolCheckpointServiceTests {

    private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
    private final PipelineCheckpointRepository checkpoints = mock(PipelineCheckpointRepository.class);
    private final ScriptService scripts = mock(ScriptService.class);
    private final PipelineToolCheckpointService service = new PipelineToolCheckpointService(
            runs, checkpoints, new PipelineFailureClassifier(), scripts);
    private final PipelineExecutionContext context = new PipelineExecutionContext(
            11L, "run-1", "conversation-1", 1);

    @Test
    void successfulCheckpointReturnsStoredOutput() {
        CheckpointDescriptor descriptor = descriptor(CheckpointReplayPolicy.SAFE_REPLAY);
        when(checkpoints.find(11L, descriptor.checkpointKey())).thenReturn(PipelineCheckpoint.builder()
                .status(PipelineCheckpointStatus.SUCCEEDED)
                .outputJson("{\"scriptEpisodeId\":52}")
                .build());

        CheckpointDecision decision = service.beforeExecute(context, descriptor, "{}");

        assertThat(decision.action()).isEqualTo(CheckpointDecision.Action.RETURN_STORED);
        assertThat(decision.storedOutput()).isEqualTo("{\"scriptEpisodeId\":52}");
        verify(checkpoints, never()).upsertRunning(any(), any(), any());
    }

    @Test
    void unknownNeverReplayCheckpointRequiresManualHandling() {
        CheckpointDescriptor descriptor = descriptor(CheckpointReplayPolicy.NEVER_REPLAY);
        when(checkpoints.find(11L, descriptor.checkpointKey())).thenReturn(PipelineCheckpoint.builder()
                .status(PipelineCheckpointStatus.UNKNOWN)
                .build());

        CheckpointDecision decision = service.beforeExecute(context, descriptor, "{}");

        assertThat(decision.action()).isEqualTo(CheckpointDecision.Action.REQUIRE_MANUAL);
        verify(checkpoints, never()).upsertRunning(any(), any(), any());
    }

    @Test
    void toolBusinessErrorMarksCheckpointFailed() {
        CheckpointDescriptor descriptor = descriptor(CheckpointReplayPolicy.SAFE_REPLAY);

        service.recordResult(context, descriptor, "{\"status\":\"error\",\"message\":\"version conflict\"}");

        verify(checkpoints).markFailed(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(descriptor.checkpointKey()),
                org.mockito.ArgumentMatchers.argThat(failure ->
                        failure.category() == PipelineFailureCategory.BUSINESS_ERROR
                                && failure.message().equals("version conflict")));
        verify(checkpoints, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void episodeWriterCannotSucceedWithoutDatabaseScenes() {
        CheckpointDescriptor descriptor = episodeWriterDescriptor();
        when(scripts.listScenesByEpisode(52L)).thenReturn(java.util.List.of());

        String verified = service.recordResult(context, descriptor, "{\"scriptEpisodeId\":52}", """
                {"status":"success","scriptEpisodeId":52,"expectedSceneCount":5,"savedSceneCount":5}
                """);

        assertThat(verified).contains("\"status\":\"error\"").contains("实际场次数");
        verify(checkpoints).markFailed(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(descriptor.checkpointKey()),
                org.mockito.ArgumentMatchers.argThat(failure -> failure.message().contains("实际场次数")));
        verify(checkpoints, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void episodeWriterSucceedsOnlyWhenStructuredProofMatchesDatabase() {
        CheckpointDescriptor descriptor = episodeWriterDescriptor();
        when(scripts.listScenesByEpisode(52L)).thenReturn(java.util.List.of(
                ScriptSceneItem.builder().id(1L).build(), ScriptSceneItem.builder().id(2L).build()));
        String result = "{\"status\":\"success\",\"scriptEpisodeId\":52,\"expectedSceneCount\":2,\"savedSceneCount\":2}";

        String verified = service.recordResult(context, descriptor, "{\"scriptEpisodeId\":52}", result);

        assertThat(verified).isEqualTo(result);
        verify(checkpoints).markSucceeded(11L, descriptor.checkpointKey(), result);
    }

    private CheckpointDescriptor descriptor(CheckpointReplayPolicy replayPolicy) {
        return new CheckpointDescriptor(
                "save_script_episode:40:12",
                "save_script_episode",
                "episode",
                "40:12",
                replayPolicy);
    }

    private CheckpointDescriptor episodeWriterDescriptor() {
        return new CheckpointDescriptor(
                "episode_scene_writer:52", "episode_scene_writer", "sub_agent", "52",
                CheckpointReplayPolicy.SAFE_REPLAY);
    }
}
