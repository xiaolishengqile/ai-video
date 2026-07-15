package com.stonewu.fusion.service.ai.agentscope;

import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDecision;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDescriptor;
import com.stonewu.fusion.service.ai.pipeline.CheckpointReplayPolicy;
import com.stonewu.fusion.service.ai.pipeline.PipelineExecutionContext;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointService;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointPolicyRegistry;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeToolAdapterTests {

    @Test
    void callAsyncShouldPreserveToolCallIdAndName() {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ToolExecutionContext toolContext = ToolExecutionContext.builder().build();
        when(toolExecutor.getToolName()).thenReturn("asset_query");
        when(toolExecutor.getToolDescription()).thenReturn("query asset");
        when(toolExecutor.getParametersSchema()).thenReturn("{}");
        when(toolExecutor.execute(eq("{\"keyword\":\"cat\"}"), any(ToolExecutionContext.class)))
                .thenReturn("ok");

        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter(
                toolExecutor,
                toolContext,
                new AgentCancellationToken(() -> false));

        ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                .id("call-123")
                .name("asset_query")
                .input(Map.of("keyword", "cat"))
                .build();

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(toolUseBlock)
                .input(Map.of("keyword", "cat"))
                .build();

        ToolResultBlock result = adapter.callAsync(param).block();

        assertEquals("call-123", result.getId());
        assertEquals("asset_query", result.getName());
                TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().getFirst());
                assertEquals("ok", output.getText());
    }

    @Test
    void pipelineToolRecordsRunningAndSucceededCheckpoint() {
        Fixture fixture = new Fixture();
        when(fixture.executor.execute(eq("{\"episodeNumber\":12,\"scriptId\":40}"), any()))
                .thenReturn("{\"status\":\"success\",\"scriptEpisodeId\":52}");
        when(fixture.policies.describe("save_script_episode", fixture.input))
                .thenReturn(Optional.of(fixture.descriptor));
        when(fixture.checkpoints.beforeExecute(fixture.context, fixture.descriptor, fixture.input))
                .thenReturn(CheckpointDecision.execute());

        ToolResultBlock result = fixture.adapter(false).callAsync(fixture.param()).block();

        assertEquals("call-episode-12", result.getId());
        verify(fixture.checkpoints).beforeExecute(fixture.context, fixture.descriptor, fixture.input);
        verify(fixture.checkpoints).recordResult(
                fixture.context,
                fixture.descriptor,
                "{\"status\":\"success\",\"scriptEpisodeId\":52}");
    }

    @Test
    void succeededCheckpointReturnsStoredOutputWithoutExecutingTool() {
        Fixture fixture = new Fixture();
        when(fixture.policies.describe("save_script_episode", fixture.input))
                .thenReturn(Optional.of(fixture.descriptor));
        when(fixture.checkpoints.beforeExecute(fixture.context, fixture.descriptor, fixture.input))
                .thenReturn(CheckpointDecision.returnStored("{\"scriptEpisodeId\":52}"));

        ToolResultBlock result = fixture.adapter(false).callAsync(fixture.param()).block();

        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().getFirst());
        assertEquals("{\"scriptEpisodeId\":52}", output.getText());
        verify(fixture.executor, never()).execute(any(), any());
        verify(fixture.checkpoints, never()).recordResult(any(), any(), any());
    }

    @Test
    void unknownNeverReplayCheckpointRequiresManualHandling() {
        Fixture fixture = new Fixture();
        when(fixture.policies.describe("save_script_episode", fixture.input))
                .thenReturn(Optional.of(fixture.descriptor));
        when(fixture.checkpoints.beforeExecute(fixture.context, fixture.descriptor, fixture.input))
                .thenReturn(CheckpointDecision.requireManual("检查点状态无法安全确认"));

        ToolResultBlock result = fixture.adapter(false).callAsync(fixture.param()).block();

        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().getFirst());
        org.assertj.core.api.Assertions.assertThat(output.getText())
                .contains("status", "error", "检查点状态无法安全确认");
        verify(fixture.executor, never()).execute(any(), any());
    }

    @Test
    void cancellationIsCheckedBeforeCheckpointMutation() {
        Fixture fixture = new Fixture();

        assertThrows(AgentCancelledException.class,
                () -> fixture.adapter(true).callAsync(fixture.param()).block());

        verify(fixture.policies, never()).describe(any(), any());
        verify(fixture.checkpoints, never()).beforeExecute(any(), any(), any());
    }

    private static final class Fixture {
        private final ToolExecutor executor = mock(ToolExecutor.class);
        private final PipelineToolCheckpointPolicyRegistry policies = mock(PipelineToolCheckpointPolicyRegistry.class);
        private final PipelineToolCheckpointService checkpoints = mock(PipelineToolCheckpointService.class);
        private final PipelineExecutionContext context = new PipelineExecutionContext(
                11L, "run-1", "conversation-1", 1);
        private final String input = "{\"episodeNumber\":12,\"scriptId\":40}";
        private final CheckpointDescriptor descriptor = new CheckpointDescriptor(
                "save_script_episode:40:12",
                "save_script_episode",
                "episode",
                "40:12",
                CheckpointReplayPolicy.SAFE_REPLAY);

        private Fixture() {
            when(executor.getToolName()).thenReturn("save_script_episode");
            when(executor.getToolDescription()).thenReturn("save episode");
            when(executor.getParametersSchema()).thenReturn("{}");
        }

        private AgentScopeToolAdapter adapter(boolean cancelled) {
            return new AgentScopeToolAdapter(
                    executor,
                    ToolExecutionContext.builder().build(),
                    new AgentCancellationToken(() -> cancelled),
                    context,
                    policies,
                    checkpoints);
        }

        private ToolCallParam param() {
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("scriptId", 40);
            inputMap.put("episodeNumber", 12);
            ToolUseBlock block = ToolUseBlock.builder()
                    .id("call-episode-12")
                    .name("save_script_episode")
                    .input(inputMap)
                    .build();
            return ToolCallParam.builder().toolUseBlock(block).input(block.getInput()).build();
        }
    }
}
