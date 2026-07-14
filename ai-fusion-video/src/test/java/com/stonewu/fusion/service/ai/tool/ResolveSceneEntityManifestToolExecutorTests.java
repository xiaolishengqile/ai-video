package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.SceneEntityManifestService;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveSceneEntityManifestToolExecutorTests {

    @Mock
    private SceneEntityManifestService manifestService;

    @Mock
    private ProjectService projectService;
    @Mock
    private ScriptService scriptService;

    @InjectMocks
    private ResolveSceneEntityManifestToolExecutor executor;

    @Test
    void executeReportsAutoCreatedAndFilteredEntities() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).episodeNumber(1).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(manifestService.resolve(any(), any(), any(), any(), anyMap())).thenReturn(new SceneEntityManifest(1, List.of(
                entity("背景烟雾", "atmospheric"), entity("能量核心", "auto_created_episode_catalog"),
                entity("第四道具", "filtered_limit"))));

        String result = executor.execute("{\"projectId\":1,\"scriptEpisodeId\":2,\"entities\":[]}", ToolExecutionContext.builder().userId(9L).build());

        assertThat(JSONUtil.parseObj(result).getInt("filteredCount")).isEqualTo(1);
        assertThat(JSONUtil.parseObj(result).getInt("autoCreatedCount")).isEqualTo(1);
        assertThat(JSONUtil.parseObj(result).getJSONArray("assetResolutionFeedback").getJSONObject(0).getStr("status"))
                .isEqualTo("unmatched_created");
    }

    @Test
    void executePassesTheAiSelectedAssetIdByEntityKeyWithoutPersistingItInTheManifest() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).episodeNumber(1).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(manifestService.resolve(any(), any(), any(), any(), anyMap()))
                .thenReturn(new SceneEntityManifest(1, List.of(entity("凌炽", "matched_selected"))));

        String result = executor.execute("""
                {"projectId":1,"scriptEpisodeId":2,"entities":[
                  {"key":"character:ling-jin","name":"凌炽","assetType":"character", "entitySubtype":"person","importance":"core","selectedAssetId":10}
                ]}
                """, ToolExecutionContext.builder().userId(9L).build());

        assertThat(JSONUtil.parseObj(result).getInt("selectedCount")).isEqualTo(1);
        verify(manifestService).resolve(eq(1L), eq(9L), eq(1), any(SceneEntityManifest.class),
                eq(Map.of("character:ling-jin", 10L)));
    }

    private static SceneEntity entity(String name, String source) {
        return new SceneEntity("prop:" + name, name, "prop", "vehicle", "atmospheric", false,
                null, null, source);
    }
}
