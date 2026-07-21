package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.config.ai.AiAgentDefinition;
import com.stonewu.fusion.config.ai.AiAgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineToolCheckpointPolicyRegistryTests {

    private final PipelineToolCheckpointPolicyRegistry registry = new PipelineToolCheckpointPolicyRegistry();

    @Test
    void buildsStableBusinessCheckpointKeys() {
        CheckpointDescriptor episode = registry.describe(
                        "save_script_episode",
                        "{\"title\":\"第十二集\",\"episodeNumber\":12,\"scriptId\":40}")
                .orElseThrow();
        CheckpointDescriptor scene = registry.describe(
                        "save_script_scene_items",
                        "{\"scriptEpisodeId\":52,\"episode_version\":3,\"scenes\":[]}")
                .orElseThrow();

        assertThat(episode.checkpointKey()).isEqualTo("save_script_episode:40:12");
        assertThat(episode.replayPolicy()).isEqualTo(CheckpointReplayPolicy.SAFE_REPLAY);
        assertThat(scene.checkpointKey()).startsWith("save_script_scene_items:52:3:");
        assertThat(scene.replayPolicy()).isEqualTo(CheckpointReplayPolicy.VERIFY_BEFORE_REPLAY);
    }

    @Test
    void sceneSaveCheckpointIncludesTheWholeBatchIdentity() {
        CheckpointDescriptor firstBatch = registry.describe(
                        "save_script_scene_items",
                        "{\"scriptEpisodeId\":52,\"episode_version\":3,\"overwriteMode\":true,\"scenes\":[{\"scene_heading\":\"1-1\"}]}")
                .orElseThrow();
        CheckpointDescriptor secondBatch = registry.describe(
                        "save_script_scene_items",
                        "{\"scriptEpisodeId\":52,\"episode_version\":3,\"overwriteMode\":false,\"scenes\":[{\"scene_heading\":\"1-2\"}]}")
                .orElseThrow();

        assertThat(firstBatch.checkpointKey()).isNotEqualTo(secondBatch.checkpointKey());
    }

    @Test
    void storyboardAssetMatchSubAgentCheckpointUsesStoryboardItemId() {
        CheckpointDescriptor descriptor = registry.describe(
                        "match_storyboard_item_assets",
                        "{\"message\":\"请为分镜镜头匹配当前集资产。\\nstoryboardItemId: 2503\\nprojectId: 18\"}")
                .orElseThrow();

        assertThat(descriptor.checkpointKey()).isEqualTo("match_storyboard_item_assets:2503");
        assertThat(descriptor.scopeId()).isEqualTo("2503");
    }

    @Test
    void generationWithoutTargetBusinessIdNeverAutoReplays() {
        CheckpointDescriptor image = registry.describe("generate_image", "{\"prompt\":\"cat\"}")
                .orElseThrow();
        CheckpointDescriptor video = registry.describe(
                        "generate_video", "{\"prompt\":\"cat\",\"duration\":5}")
                .orElseThrow();

        assertThat(image.replayPolicy()).isEqualTo(CheckpointReplayPolicy.NEVER_REPLAY);
        assertThat(video.replayPolicy()).isEqualTo(CheckpointReplayPolicy.NEVER_REPLAY);
    }

    @Test
    void everyRegisteredPipelineToolHasExplicitReadOrWritePolicy() {
        AiAgentRegistry agents = new AiAgentRegistry();

        for (AiAgentDefinition agent : agents.getAll()) {
            List<String> tools = agent.getToolNames() == null ? List.of() : agent.getToolNames();
            for (String tool : tools) {
                assertThat(registry.isReadOnly(tool) || registry.isClassified(tool))
                        .as("agent=%s tool=%s", agent.getType(), tool)
                        .isTrue();
            }
            List<AiAgentDefinition.SubAgentToolDef> subAgents = agent.getSubAgentTools() == null
                    ? List.of()
                    : agent.getSubAgentTools();
            for (AiAgentDefinition.SubAgentToolDef subAgent : subAgents) {
                assertThat(registry.isClassified(subAgent.getToolName()))
                        .as("agent=%s subAgent=%s", agent.getType(), subAgent.getToolName())
                        .isTrue();
            }
        }
    }
}
