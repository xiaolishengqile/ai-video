package com.stonewu.fusion.config.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentRegistryTests {

    @Test
    void fullScriptParserCarriesCurrentScriptIdIntoEveryWriteCall() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("script_full_parse");

        assertThat(definition.getInstructionTemplate())
                .contains("<project_id>{projectId}</project_id>")
                .contains("<script_id>{scriptId}</script_id>")
                .doesNotContain("{scriptContent}");
        assertThat(definition.getSystemPrompt())
                .contains("所有写入工具调用")
                .contains("scriptId")
                .contains("<script_id>");
    }

    @Test
    void scriptSceneWriterOverrideUsesTheManifestAssetBindingWorkflow() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("script_full_parse");
        AiAgentDefinition.SubAgentToolDef subAgent = definition.getSubAgentTools().getFirst();
        String prompt = new AiAgentRegistry().getByType(subAgent.getRefAgentType()).getSystemPrompt();

        assertThat(subAgent.getSystemPromptOverride()).isNull();
        assertThat(subAgent.getOutputSchema())
                .contains("expectedSceneCount", "savedSceneCount")
                .doesNotContain("\"sceneCount\"");
        assertThat(prompt)
                .contains("search_episode_asset_candidates")
                .contains("resolve_scene_entity_manifest")
                .contains("三类独立存在、可同时存在")
                .contains("scriptEpisodeId")
                .contains("ambiguous_episode_catalog")
                .contains("selectedAssetId")
                .contains("无图片占位资产")
                .doesNotContain("list_project_assets")
                .doesNotContain("绝不自动创建项目级资产")
                .doesNotContain("character_asset_ids:");
    }

    @Test
    void parsingAgentsUseOnePersistedAssetCatalogSnapshotPerRun() {
        AiAgentRegistry registry = new AiAgentRegistry();

        assertThat(registry.getByType("script_full_parse").getToolNames())
                .contains("create_project_asset_catalog_snapshot")
                .doesNotContain("list_project_assets", "batch_create_assets");
        assertThat(registry.getByType("script_full_parse").getSystemPrompt())
                .contains("create_project_asset_catalog_snapshot")
                .contains("场次解析完成后");
        assertThat(registry.getByType("script_to_storyboard").getSystemPrompt())
                .contains("create_project_asset_catalog_snapshot")
                .contains("assetCatalogSnapshotId");
        assertThat(registry.getByType("episode_scene_writer").getToolNames())
                .contains("search_episode_asset_candidates")
                .doesNotContain("get_project_asset_catalog_snapshot", "list_project_assets");
        assertThat(registry.getByType("episode_storyboard_writer").getSystemPrompt())
                .contains("get_project_asset_catalog_snapshot")
                .contains("固定资产目录");
    }

    @Test
    void storyboardPreprocessorUsesSceneBindingsInsteadOfAProjectWideAssetCatalog() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("storyboard_asset_preprocessor");

        assertThat(definition.getToolNames())
                .contains("get_script_episode", "query_asset_items")
                .doesNotContain("list_project_assets");
        assertThat(definition.getSystemPrompt())
                .contains("entityManifest")
                .doesNotContain("调用 list_project_assets");
    }

    @Test
    void storyboardAgentsRequireAnEpisodeSnapshotAndCannotFallBackToProjectWideAssets() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("script_to_storyboard");
        AiAgentDefinition.SubAgentToolDef writer = definition.getSubAgentTools().stream()
                .filter(tool -> "episode_storyboard_writer".equals(tool.getToolName()))
                .findFirst().orElseThrow();

        assertThat(definition.getToolNames()).doesNotContain("list_project_assets");
        assertThat(writer.getParametersSchema())
                .contains("assetCatalogSnapshotId")
                .contains("storyboardId")
                .contains("\"required\": [\"scriptEpisodeId\", \"storyboardId\", \"assetCatalogSnapshotId\"]");
        assertThat(new AiAgentRegistry().getByType("episode_storyboard_writer").getToolNames())
                .doesNotContain("list_project_assets");
        assertThat(new AiAgentRegistry().getByType("episode_storyboard_writer").getSystemPrompt())
                .contains("blocked_missing_assets")
                .contains("待补资产后重跑");
    }

    @Test
    void uploadedSingleEpisodeParsingUsesTheSameCurrentEpisodeCandidateFlow() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("script_episode_parse");

        assertThat(definition.getToolNames())
                .contains("search_episode_asset_candidates", "resolve_scene_entity_manifest")
                .doesNotContain("list_project_assets", "batch_create_assets");
        assertThat(definition.getSystemPrompt())
                .contains("ambiguous_episode_catalog")
                .contains("selectedAssetId");
    }
}
