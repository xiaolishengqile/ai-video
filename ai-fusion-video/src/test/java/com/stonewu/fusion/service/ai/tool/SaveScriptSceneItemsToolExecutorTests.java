package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaveScriptSceneItemsToolExecutorTests {

    @Mock
    private ScriptService scriptService;

    @Mock
    private ScriptSceneItemMapper sceneItemMapper;

    @Mock
    private AssetService assetService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private SaveScriptSceneItemsToolExecutor executor;

    @InjectMocks
    private ScriptSceneItemDetailQueryToolExecutor detailExecutor;

    @InjectMocks
    private ScriptEpisodeDetailQueryToolExecutor episodeDetailExecutor;

    private final ToolExecutionContext context = ToolExecutionContext.builder().userId(9L).build();

    @BeforeEach
    void setUp() {
        lenient().when(scriptService.getEpisodeById(1L)).thenReturn(ScriptEpisode.builder().id(1L).scriptId(10L).version(1).build());
        lenient().when(scriptService.getById(10L)).thenReturn(Script.builder().id(10L).projectId(1L).build());
        lenient().when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        stubAsset(100L, 500L, "scene");
        stubAsset(101L, 501L, "character");
        stubAsset(102L, 502L, "prop");
    }

    @Test
    void saveUsesResolvedManifestWhenLegacyAssetIdsDisagree() {
        executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜",
                  "scene_asset_id":99,
                  "entity_manifest":%s
                }]}""".formatted(manifestJson()), context);

        ArgumentCaptor<List<ScriptSceneItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(scriptService).batchSaveSceneItems(anyLong(), anyInt(), captor.capture(), anyBoolean());
        assertThat(captor.getValue().getFirst().getSceneAssetId()).isEqualTo(100L);
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
    void saveRejectsAtmosphericEntityThatCarriesAssetDefaults() {
        String atmosphericManifest = new SceneEntityManifest(1, List.of(
                new SceneEntity("prop:smoke", "背景烟雾", "prop", "collective", "atmospheric", true,
                        103L, 503L, "forged"))).toJson();

        String result = executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜",
                  "entity_manifest":%s
                }]}""".formatted(atmosphericManifest), context);

        assertThat(result).contains("atmospheric 实体不能携带资产 ID 或默认继承标记");
        verify(scriptService, never()).batchSaveSceneItems(anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void saveKeepsUnmatchedEpisodeEntityWithoutLegacyAssetIds() {
        String unmatched = new SceneEntityManifest(1, List.of(
                new SceneEntity("prop:core", "能量核心", "prop", "device", "core", true,
                        null, null, "unmatched_episode_catalog"))).toJson();

        executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"内景 实验室 日", "entity_manifest":%s
                }]}""".formatted(unmatched), context);

        ArgumentCaptor<List<ScriptSceneItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(scriptService).batchSaveSceneItems(anyLong(), anyInt(), captor.capture(), anyBoolean());
        assertThat(captor.getValue().getFirst()).satisfies(scene -> {
            assertThat(scene.getSceneAssetId()).isNull();
            assertThat(scene.getCharacterAssetIds()).isEqualTo("[]");
            assertThat(scene.getPropAssetIds()).isEqualTo("[]");
            assertThat(scene.getEntityManifest()).isEqualTo(unmatched);
        });
    }

    @Test
    void saveRejectsAnAmbiguousEntityUntilTheAiSelectsOneCurrentEpisodeCandidate() {
        String ambiguous = new SceneEntityManifest(1, List.of(
                new SceneEntity("character:ling-jin", "凌炽", "character", "person", "core", true,
                        null, null, "ambiguous_episode_catalog"))).toJson();

        String result = executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"内景 实验室 日", "entity_manifest":%s
                }]}""".formatted(ambiguous), context);

        assertThat(result).contains("候选资产存在歧义");
        verify(scriptService, never()).batchSaveSceneItems(anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void saveNormalizesForgedCoreManifestToDefaultForShots() {
        String forged = new SceneEntityManifest(1, List.of(
                new SceneEntity("scene:station", "撤离站台", "scene", "station", "core", false,
                        100L, 500L, "forged"))).toJson();

        executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜", "entity_manifest":%s
                }]}""".formatted(forged), context);

        ArgumentCaptor<List<ScriptSceneItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(scriptService).batchSaveSceneItems(anyLong(), anyInt(), captor.capture(), anyBoolean());
        assertThat(SceneEntityManifest.fromJson(captor.getValue().getFirst().getEntityManifest())
                .entities().getFirst().defaultForShots()).isTrue();
    }

    @Test
    void saveRejectsManifestItemThatDoesNotBelongToDeclaredAsset() {
        when(assetService.getItemById(500L)).thenReturn(AssetItem.builder().id(500L).assetId(999L).build());

        String result = executor.execute("""
                {"scriptEpisodeId":1,"episode_version":1,"scenes":[{
                  "scene_heading":"外景 撤离站台 夜", "entity_manifest":%s
                }]}""".formatted(manifestJson()), context);

        assertThat(result).contains("资产关联不属于当前项目或类型不匹配");
    }

    private void stubAsset(Long assetId, Long itemId, String type) {
        lenient().when(assetService.getById(assetId)).thenReturn(Asset.builder().id(assetId).projectId(1L).type(type).build());
        lenient().when(assetService.getItemById(itemId)).thenReturn(AssetItem.builder().id(itemId).assetId(assetId).build());
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
    void sceneDetailNeverExposesAtmosphericEntityAsDefaultAsset() {
        String atmosphericManifest = new SceneEntityManifest(1, List.of(
                new SceneEntity("prop:smoke", "背景烟雾", "prop", "collective", "atmospheric", true,
                        103L, 503L, "forged"))).toJson();
        when(sceneItemMapper.selectById(1L)).thenReturn(ScriptSceneItem.builder()
                .id(1L)
                .entityManifest(atmosphericManifest)
                .build());

        var result = JSONUtil.parseObj(detailExecutor.execute("{\"scriptSceneItemId\":1}", context));

        assertThat(result.getJSONArray("defaultPropAssetItemIds").toList(Long.class)).isEmpty();
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
                        100L, 500L, "auto_created_episode_catalog"),
                new SceneEntity("character:evacuees", "撤离士兵群", "character", "collective", "core", true,
                        101L, 501L, "auto_created_episode_catalog"),
                new SceneEntity("prop:train", "装甲列车", "prop", "vehicle", "core", true,
                        102L, 502L, "auto_created_episode_catalog"))).toJson();
    }
}
