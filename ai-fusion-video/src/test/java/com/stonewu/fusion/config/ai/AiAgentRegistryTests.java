package com.stonewu.fusion.config.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentRegistryTests {

    @Test
    void scriptSceneWriterOverrideUsesTheManifestAssetBindingWorkflow() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("script_full_parse");
        AiAgentDefinition.SubAgentToolDef subAgent = definition.getSubAgentTools().getFirst();
        String prompt = new AiAgentRegistry().getByType(subAgent.getRefAgentType()).getSystemPrompt();

        assertThat(subAgent.getSystemPromptOverride()).isNull();
        assertThat(prompt)
                .contains("list_project_assets")
                .contains("resolve_scene_entity_manifest")
                .contains("三类独立存在、可同时存在")
                .contains("当前 scriptEpisodeId")
                .contains("绝不自动创建项目级资产")
                .doesNotContain("character_asset_ids:");
    }

    @Test
    void parsingAgentsUseOnePersistedAssetCatalogSnapshotPerRun() {
        AiAgentRegistry registry = new AiAgentRegistry();

        assertThat(registry.getByType("script_full_parse").getToolNames())
                .contains("create_project_asset_catalog_snapshot");
        assertThat(registry.getByType("script_full_parse").getSystemPrompt())
                .contains("create_project_asset_catalog_snapshot")
                .contains("assetCatalogSnapshotId");
        assertThat(registry.getByType("script_to_storyboard").getSystemPrompt())
                .contains("create_project_asset_catalog_snapshot")
                .contains("assetCatalogSnapshotId");
        assertThat(registry.getByType("episode_scene_writer").getToolNames())
                .contains("get_project_asset_catalog_snapshot");
        assertThat(registry.getByType("episode_storyboard_writer").getSystemPrompt())
                .contains("get_project_asset_catalog_snapshot")
                .contains("固定资产目录");
    }
}
