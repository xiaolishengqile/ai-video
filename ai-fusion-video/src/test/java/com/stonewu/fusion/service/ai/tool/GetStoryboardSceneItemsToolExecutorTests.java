package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetStoryboardSceneItemsToolExecutorTests {

    @Mock
    private StoryboardService storyboardService;
    @Mock
    private AssetService assetService;

    @InjectMocks
    private GetStoryboardSceneItemsToolExecutor executor;

    @Test
    void executeIncludesParentAssetIdentityAndOnlyImageBackedVisualReferences() {
        when(storyboardService.getSceneById(1L)).thenReturn(StoryboardScene.builder().id(1L).sceneHeading("站台").build());
        when(storyboardService.listItemsByScene(1L)).thenReturn(List.of(
                StoryboardItem.builder().id(2L).characterIds("[11,12]").build()));
        when(assetService.getItemById(11L)).thenReturn(AssetItem.builder().id(11L).assetId(10L).name("正面").itemType("initial")
                .imageUrl("https://example.test/lingjin.png").build());
        when(assetService.getItemById(12L)).thenReturn(AssetItem.builder().id(12L).assetId(10L).name("空白").itemType("initial").build());
        when(assetService.getById(10L)).thenReturn(Asset.builder().id(10L).name("凌炽").type("character").build());

        String result = executor.execute("{\"storyboardSceneId\":1}", ToolExecutionContext.builder().userId(9L).build());

        var refs = JSONUtil.parseObj(result).getJSONArray("items").getJSONObject(0).getJSONArray("characterRefs");
        assertThat(refs).singleElement().satisfies(rawRef -> {
            var ref = JSONUtil.parseObj(rawRef);
            assertThat(ref.getLong("assetItemId")).isEqualTo(11L);
            assertThat(ref.getStr("assetName")).isEqualTo("凌炽");
            assertThat(ref.getBool("hasImage")).isTrue();
        });
    }

    @Test
    void executeReturnsSceneRefsForThePrimaryScene() {
        when(storyboardService.getSceneById(1L)).thenReturn(StoryboardScene.builder().id(1L).sceneHeading("站台").build());
        when(storyboardService.listItemsByScene(1L)).thenReturn(List.of(
                StoryboardItem.builder().id(2L).sceneAssetItemId(21L).sceneAssetItemIds("[21,22]").build()));
        when(assetService.getItemById(21L)).thenReturn(AssetItem.builder().id(21L).assetId(20L).name("月台").itemType("initial")
                .imageUrl("https://example.test/platform.png").build());
        when(assetService.getItemById(22L)).thenReturn(AssetItem.builder().id(22L).assetId(20L).name("站牌").itemType("initial")
                .imageUrl("https://example.test/sign.png").build());
        when(assetService.getById(20L)).thenReturn(Asset.builder().id(20L).name("月台").type("scene").build());

        String result = executor.execute("{\"storyboardSceneId\":1}", ToolExecutionContext.builder().userId(9L).build());

        var item = JSONUtil.parseObj(result).getJSONArray("items").getJSONObject(0);
        assertThat(item.getJSONObject("sceneRef").getLong("assetItemId")).isEqualTo(21L);
        assertThat(item.getJSONArray("sceneRefs").size()).isEqualTo(2);
    }
}
