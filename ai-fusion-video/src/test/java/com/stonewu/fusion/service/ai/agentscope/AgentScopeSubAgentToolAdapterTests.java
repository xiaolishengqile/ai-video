package com.stonewu.fusion.service.ai.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
