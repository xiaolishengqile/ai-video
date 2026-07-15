package com.stonewu.fusion.service.ai.agentscope;

import com.stonewu.fusion.service.ai.pipeline.CheckpointDecision;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDescriptor;
import com.stonewu.fusion.service.ai.pipeline.CheckpointReplayPolicy;
import com.stonewu.fusion.service.ai.pipeline.PipelineExecutionContext;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointPolicyRegistry;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentScopeSubAgentToolAdapterTests {

    @Test
    void exposesTheDeclaredBusinessParameterSchemaInsteadOfAHardCodedMessageField() {
        AgentScopeSubAgentToolAdapter adapter = new AgentScopeSubAgentToolAdapter(
                "episode_storyboard_writer", "写分镜", """
                        {"type":"object","properties":{"scriptEpisodeId":{"type":"integer"},"assetCatalogSnapshotId":{"type":"integer"}},"required":["scriptEpisodeId","assetCatalogSnapshotId"]}
                        """, null, null, null);

        assertThat(adapter.getParameters().get("properties").toString())
                .contains("scriptEpisodeId", "assetCatalogSnapshotId")
                .doesNotContain("message");
        assertThat(adapter.getParameters().get("required").toString())
                .contains("scriptEpisodeId", "assetCatalogSnapshotId");
    }

    @Test
    void succeededParentCheckpointDoesNotRecreateSubAgent() {
        PipelineToolCheckpointPolicyRegistry policies = mock(PipelineToolCheckpointPolicyRegistry.class);
        PipelineToolCheckpointService checkpoints = mock(PipelineToolCheckpointService.class);
        PipelineExecutionContext context = new PipelineExecutionContext(11L, "run-1", "conversation-1", 1);
        CheckpointDescriptor descriptor = new CheckpointDescriptor(
                "episode_scene_writer:52",
                "episode_scene_writer",
                "sub_agent",
                "52",
                CheckpointReplayPolicy.SAFE_REPLAY);
        String input = "{\"scriptEpisodeId\":52}";
        when(policies.describe("episode_scene_writer", input)).thenReturn(Optional.of(descriptor));
        when(checkpoints.beforeExecute(context, descriptor, input))
                .thenReturn(CheckpointDecision.returnStored("{\"sceneCount\":8}"));
        java.util.function.Supplier<io.agentscope.core.ReActAgent> factory = mock(java.util.function.Supplier.class);
        AgentScopeSubAgentToolAdapter adapter = new AgentScopeSubAgentToolAdapter(
                "episode_scene_writer", "写场次", "{}", factory, null,
                new AgentCancellationToken(() -> false), context, policies, checkpoints);
        ToolUseBlock block = ToolUseBlock.builder()
                .id("parent-call-1")
                .name("episode_scene_writer")
                .input(Map.of("scriptEpisodeId", 52))
                .build();

        ToolResultBlock result = adapter.callAsync(ToolCallParam.builder()
                .toolUseBlock(block)
                .input(block.getInput())
                .build()).block();

        assertThat(((TextBlock) result.getOutput().getFirst()).getText()).isEqualTo("{\"sceneCount\":8}");
        verifyNoInteractions(factory);
    }
}
