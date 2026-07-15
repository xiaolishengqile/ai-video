package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
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
    private final PipelineToolCheckpointService service = new PipelineToolCheckpointService(
            runs, checkpoints, new PipelineFailureClassifier());
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

    private CheckpointDescriptor descriptor(CheckpointReplayPolicy replayPolicy) {
        return new CheckpointDescriptor(
                "save_script_episode:40:12",
                "save_script_episode",
                "episode",
                "40:12",
                replayPolicy);
    }
}
