package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.SceneEntityManifestService;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaveStoryboardSceneShotsToolExecutorTests {

    @Mock
    private StoryboardService storyboardService;

    @Mock
    private ScriptSceneItemMapper scriptSceneItemMapper;

    @Mock
    private SceneEntityManifestService manifestService;

    @Mock
    private ScriptService scriptService;

    @Mock
    private AssetService assetService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private SaveStoryboardSceneShotsToolExecutor executor;

    private final ToolExecutionContext context = ToolExecutionContext.builder().userId(9L).build();

    @BeforeEach
    void setUp() {
        lenient().when(storyboardService.getById(10L)).thenReturn(Storyboard.builder().id(10L).projectId(1L).build());
        lenient().when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        lenient().when(storyboardService.getEpisodeById(20L)).thenReturn(StoryboardEpisode.builder()
                .id(20L).storyboardId(10L).scriptEpisodeId(30L).build());
        lenient().when(scriptService.getEpisodeById(30L)).thenReturn(ScriptEpisode.builder()
                .id(30L).episodeNumber(1).build());
        lenient().when(scriptSceneItemMapper.selectById(1L)).thenReturn(ScriptSceneItem.builder()
                .id(1L).episodeId(30L).entityManifest(manifestJson()).build());
        lenient().when(storyboardService.createScene(any())).thenAnswer(invocation -> {
            StoryboardScene scene = invocation.getArgument(0);
            scene.setId(40L);
            return scene;
        });
        stubItem(501L, 101L, "scene");
        stubItem(502L, 102L, "character");
        stubItem(503L, 103L, "prop");
        stubItem(598L, 105L, "scene");
        stubItem(599L, 104L, "prop");
    }

    @Test
    void saveInheritsCoreSceneCrowdAndTrainWhenShotOmitsAssetIds() {
        String result = executor.execute(request("{}"), context);

        assertThat(result).contains("success");
        StoryboardItem saved = capturedItems().getFirst();
        assertThat(saved.getSceneAssetItemId()).isEqualTo(501L);
        assertThat(saved.getCharacterIds()).isEqualTo("[502]");
        assertThat(saved.getPropIds()).isEqualTo("[503]");
    }

    @Test
    void saveAllowsCloseUpToExcludeCrowdButNotOnlyCoreScene() {
        assertThat(executor.execute(request("""
                {"excludedDefaultEntityKeys":[{"key":"character:evacuees","reason":"close_up"}]}
                """), context)).contains("success");
        assertThat(capturedItems().getFirst().getCharacterIds()).isEqualTo("[]");

        assertThat(executor.execute(request("""
                {"excludedDefaultEntityKeys":[{"key":"scene:station","reason":"close_up"}]}
                """), context)).contains("不能排除该场唯一核心场景");
    }

    @Test
    void saveRejectsAnUnsupportedDefaultExclusionReasonBeforeCreatingScene() {
        String result = executor.execute(request("""
                {"excludedDefaultEntityKeys":[{"key":"character:evacuees","reason":"not_visible"}]}
                """), context);

        assertThat(result).contains("必须提供实体 key 和合法 reason");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsSceneFromAnotherScriptEpisodeBeforeCreatingScene() {
        when(scriptSceneItemMapper.selectById(1L)).thenReturn(ScriptSceneItem.builder()
                .id(1L).episodeId(31L).entityManifest(manifestJson()).build());

        String result = executor.execute(request("{}"), context);

        assertThat(result).contains("不属于分镜集关联的剧本分集");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsExplicitAssetItemWithWrongTypeBeforeCreatingScene() {
        String result = executor.execute(request("{\"sceneAssetItemId\":502}"), context);

        assertThat(result).contains("场景资产子项类型不匹配");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsDifferentExplicitSceneWhenCoreSceneMustBeInherited() {
        String result = executor.execute(request("{\"sceneAssetItemId\":598}"), context);

        assertThat(result).contains("不能替换核心默认场景");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveAllowsSameExplicitCoreSceneWithoutDuplicateBinding() {
        var result = JSONUtil.parseObj(executor.execute(request("{\"sceneAssetItemId\":501}"), context));

        assertThat(result.getStr("status")).isEqualTo("success");
        assertThat(capturedItems().getFirst().getSceneAssetItemId()).isEqualTo(501L);
        var sources = result.getJSONArray("assetBindingSources").getJSONObject(0);
        assertThat(sources.getJSONArray("inherited").toList(Long.class)).containsExactly(502L, 503L);
        assertThat(sources.getJSONArray("explicit").toList(Long.class)).containsExactly(501L);
    }

    @Test
    void saveRejectsExplicitAssetThatIsAlsoExcluded() {
        String result = executor.execute(request("""
                {"propIds":[503],"excludedDefaultEntityKeys":[{"key":"prop:train","reason":"offscreen"}]}
                """), context);

        assertThat(result).contains("不能同时排除并显式加入");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsUnauthorizedProjectBeforeCreatingScene() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(false);

        String result = executor.execute(request("{}"), context);

        assertThat(result).contains("无权访问该项目");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsAssetItemFromAnotherProjectBeforeCreatingScene() {
        when(assetService.getById(104L)).thenReturn(Asset.builder().id(104L).projectId(2L).type("prop").build());

        String result = executor.execute(request("{\"propIds\":[599]}"), context);

        assertThat(result).contains("资产不属于当前项目");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsExplicitAssetItemFromAnotherEpisodeBeforeCreatingScene() {
        when(assetService.getById(104L)).thenReturn(Asset.builder().id(104L).projectId(1L).episodeNumber(2).type("prop").build());

        String result = executor.execute(request("{\"propIds\":[599]}"), context);

        assertThat(result).contains("资产不属于当前剧集");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveRejectsExcludedDefaultAssetItemFromAnotherProjectBeforeCreatingScene() {
        when(assetService.getById(103L)).thenReturn(Asset.builder().id(103L).projectId(2L).type("prop").build());

        String result = executor.execute(request("""
                {"excludedDefaultEntityKeys":[{"key":"prop:train","reason":"offscreen"}]}
                """), context);

        assertThat(result).contains("资产不属于当前项目");
        verify(storyboardService, never()).createScene(any());
    }

    @Test
    void saveReportsInheritedExplicitAndExcludedBindings() {
        var result = JSONUtil.parseObj(executor.execute(request("""
                {"propIds":[599],"excludedDefaultEntityKeys":[{"key":"character:evacuees","reason":"offscreen"}]}
                """), context));
        var sources = result.getJSONArray("assetBindingSources").getJSONObject(0);

        assertThat(sources.getJSONArray("inherited").toList(Long.class)).containsExactly(501L, 503L);
        assertThat(sources.getJSONArray("explicit").toList(Long.class)).containsExactly(599L);
        assertThat(sources.getJSONArray("excluded").toList(Long.class)).containsExactly(502L);
    }

    @Test
    void savePersistsBindingSourceWithStoryboardItem() {
        executor.execute(request("""
                {"propIds":[599],"excludedDefaultEntityKeys":[{"key":"character:evacuees","reason":"offscreen"}]}
                """), context);

        var bindingSource = JSONUtil.parseObj(capturedItems().getFirst().getCustomData())
                .getJSONObject("assetBindingSource");
        assertThat(bindingSource.getJSONArray("inherited").toList(Long.class)).containsExactly(501L, 503L);
        assertThat(bindingSource.getJSONArray("explicit").toList(Long.class)).containsExactly(599L);
        assertThat(bindingSource.getJSONArray("excluded").toList(Long.class)).containsExactly(502L);
    }

    private String request(String shotProperties) {
        String normalized = shotProperties.trim();
        String properties = normalized.substring(1, normalized.length() - 1);
        return """
                {"storyboardId":10,"storyboardEpisodeId":20,"scriptSceneItemId":1,
                 "sceneNumber":"1-1","shots":[{"content":"裂缝映出撤离站台"%s}]}
                """.formatted(properties.isBlank() ? "" : "," + properties);
    }

    private List<StoryboardItem> capturedItems() {
        ArgumentCaptor<List<StoryboardItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(storyboardService).batchCreateItems(captor.capture());
        return captor.getValue();
    }

    private void stubItem(Long itemId, Long assetId, String type) {
        AssetItem item = AssetItem.builder().id(itemId).assetId(assetId).build();
        lenient().when(assetService.getItemById(itemId)).thenReturn(item);
        lenient().when(assetService.getById(assetId)).thenReturn(Asset.builder()
                .id(assetId).projectId(1L).episodeNumber(1).type(type).build());
    }

    private static String manifestJson() {
        return new SceneEntityManifest(1, List.of(
                new SceneEntity("scene:station", "撤离站台", "scene", "station", "core", true,
                        101L, 501L, "auto_created_episode_catalog"),
                new SceneEntity("character:evacuees", "撤离士兵群", "character", "collective", "core", true,
                        102L, 502L, "auto_created_episode_catalog"),
                new SceneEntity("prop:train", "装甲列车", "prop", "vehicle", "core", true,
                        103L, 503L, "auto_created_episode_catalog"),
                new SceneEntity("prop:warning-light", "站台警示灯", "prop", "fixture", "supporting", false,
                        104L, 504L, "auto_created_episode_catalog"))).toJson();
    }
}
