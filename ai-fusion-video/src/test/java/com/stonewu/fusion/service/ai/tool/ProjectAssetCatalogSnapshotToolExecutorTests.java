package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetCatalogSnapshotService;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAssetCatalogSnapshotToolExecutorTests {

    @Mock
    private AssetCatalogSnapshotService snapshotService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScriptService scriptService;
    @InjectMocks
    private CreateProjectAssetCatalogSnapshotToolExecutor createExecutor;
    @InjectMocks
    private GetProjectAssetCatalogSnapshotToolExecutor getExecutor;

    @Test
    void createChecksProjectAccessAndReturnsSnapshotId() {
        when(projectService.canAccessProject(11L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(18L)).thenReturn(ScriptEpisode.builder().id(18L).scriptId(7L).episodeNumber(2).build());
        when(scriptService.getById(7L)).thenReturn(Script.builder().id(7L).projectId(11L).build());
        when(snapshotService.create(11L, 7L, 18L, 2)).thenReturn(AssetCatalogSnapshot.builder()
                .id(31L).projectId(11L).scriptId(7L).scriptEpisodeId(18L).assetCount(2).build());

        String output = createExecutor.execute("{\"projectId\":11,\"scriptId\":7,\"scriptEpisodeId\":18}", context());

        assertThat(JSONUtil.parseObj(output).getLong("snapshotId")).isEqualTo(31L);
        assertThat(JSONUtil.parseObj(output).getLong("scriptEpisodeId")).isEqualTo(18L);
        verify(snapshotService).create(11L, 7L, 18L, 2);
    }

    @Test
    void getReturnsOnlyTheRequestedPersistedCatalog() {
        when(snapshotService.getById(31L)).thenReturn(AssetCatalogSnapshot.builder()
                .id(31L).projectId(11L).scriptId(7L).assetCount(1)
                .scriptEpisodeId(18L)
                .catalogJson("[{\"id\":9,\"name\":\"白雪公主\"}]").build());
        when(projectService.canAccessProject(11L, 9L)).thenReturn(true);

        String output = getExecutor.execute("{\"snapshotId\":31}", context());

        assertThat(JSONUtil.parseObj(output).getJSONArray("assets")).hasSize(1);
        assertThat(JSONUtil.parseObj(output).getLong("snapshotId")).isEqualTo(31L);
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.builder().userId(9L).build();
    }
}
