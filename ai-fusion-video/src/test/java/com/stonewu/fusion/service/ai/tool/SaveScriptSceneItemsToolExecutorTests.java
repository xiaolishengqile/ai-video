package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaveScriptSceneItemsToolExecutorTests {

    @Mock
    private ScriptService scriptService;

    @Mock
    private ScriptSceneItemMapper sceneItemMapper;

    @InjectMocks
    private SaveScriptSceneItemsToolExecutor executor;

    @InjectMocks
    private ScriptSceneItemDetailQueryToolExecutor detailExecutor;

    @InjectMocks
    private ScriptEpisodeDetailQueryToolExecutor episodeDetailExecutor;

    private final ToolExecutionContext context = ToolExecutionContext.builder().userId(9L).build();

    @Test
    void saveRejectsManifestIdsThatDisagreeWithSceneAssetIds() {
        String result = executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜",
                  "scene_asset_id":99,
                  "entity_manifest":%s
                }]}""".formatted(manifestJson()), context);

        assertThat(result).contains("entity_manifest 与场次资产关联不一致");
        verify(scriptService, never()).batchSaveSceneItems(anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void saveDerivesLegacyAssetIdsFromResolvedManifest() {
        executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜",
                  "entity_manifest":%s
                }]}""".formatted(manifestJson()), context);

        ArgumentCaptor<List<ScriptSceneItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(scriptService).batchSaveSceneItems(anyLong(), anyInt(), captor.capture(), anyBoolean());
        ScriptSceneItem saved = captor.getValue().getFirst();
        assertThat(saved.getSceneAssetId()).isEqualTo(100L);
        assertThat(saved.getCharacterAssetIds()).isEqualTo("[101]");
        assertThat(saved.getPropAssetIds()).isEqualTo("[102]");
        assertThat(saved.getEntityManifest()).isEqualTo(manifestJson());
    }

    @Test
    void sceneDetailReturnsResolvedDefaultAssetItemIds() {
        when(sceneItemMapper.selectById(1L)).thenReturn(ScriptSceneItem.builder()
                .id(1L)
                .entityManifest(manifestJson())
                .build());

        var result = JSONUtil.parseObj(detailExecutor.execute("{\"scriptSceneItemId\":1}", context));

        assertThat(result.getLong("defaultSceneAssetItemId")).isEqualTo(500L);
        assertThat(result.getJSONArray("defaultCharacterAssetItemIds").toList(Long.class)).containsExactly(501L);
        assertThat(result.getJSONArray("defaultPropAssetItemIds").toList(Long.class)).containsExactly(502L);
    }

    @Test
    void fullEpisodeReturnsManifestAndDefaultAssetItemIds() {
        when(scriptService.getEpisodeById(1L)).thenReturn(ScriptEpisode.builder().id(1L).version(1).build());
        when(scriptService.listScenesByEpisode(1L)).thenReturn(List.of(ScriptSceneItem.builder()
                .id(2L)
                .entityManifest(manifestJson())
                .build()));

        var scene = JSONUtil.parseObj(episodeDetailExecutor.execute(
                "{\"scriptEpisodeId\":1,\"detailLevel\":\"full\"}", context))
                .getJSONArray("scenes").getJSONObject(0);

        assertThat(scene.getJSONObject("entityManifest").getInt("version")).isEqualTo(1);
        assertThat(scene.getLong("defaultSceneAssetItemId")).isEqualTo(500L);
        assertThat(scene.getJSONArray("defaultCharacterAssetItemIds").toList(Long.class)).containsExactly(501L);
        assertThat(scene.getJSONArray("defaultPropAssetItemIds").toList(Long.class)).containsExactly(502L);
    }

    private static String manifestJson() {
        return new SceneEntityManifest(1, List.of(
                new SceneEntity("scene:station", "撤离站台", "scene", "station", "core", true,
                        100L, 500L, "auto_created"),
                new SceneEntity("character:evacuees", "撤离士兵群", "character", "collective", "core", true,
                        101L, 501L, "auto_created"),
                new SceneEntity("prop:train", "装甲列车", "prop", "vehicle", "core", true,
                        102L, 502L, "auto_created"))).toJson();
    }
}
