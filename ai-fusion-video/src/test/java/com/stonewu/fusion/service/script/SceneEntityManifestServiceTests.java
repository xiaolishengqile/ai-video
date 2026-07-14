package com.stonewu.fusion.service.script;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.AssetService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneEntityManifestServiceTests {

    @Mock
    private AssetService assetService;

    @InjectMocks
    private SceneEntityManifestService service;

    @Test
    void resolveCreatesCoreCollectiveAndReusesExistingTrain() {
        when(assetService.findOrCreate(any(Asset.class)))
                .thenReturn(created(10L))
                .thenReturn(reused(20L));
        when(assetService.listItems(10L)).thenReturn(List.of(AssetItem.builder().id(11L).itemType("initial").build()));
        when(assetService.listItems(20L)).thenReturn(List.of(AssetItem.builder().id(21L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, manifestWithCoreCrowdAndTrain());

        assertThat(result.entities()).allMatch(entity -> entity.assetId() != null && entity.assetItemId() != null);
        verify(assetService, times(2)).findOrCreate(any(Asset.class));
    }

    @Test
    void resolveCreatesAnInitialItemWhenAnExistingAssetHasNone() {
        when(assetService.findOrCreate(any(Asset.class))).thenReturn(reused(30L, "撤离列车站台"));
        when(assetService.listItems(30L)).thenReturn(List.of());
        when(assetService.createItem(any(AssetItem.class)))
                .thenReturn(AssetItem.builder().id(31L).assetId(30L).itemType("initial").build());

        SceneEntityManifest result = service.resolve(1L, 9L, new SceneEntityManifest(1, List.of(
                entity("scene:platform", "撤离列车站台", "scene", "station", "core"))));

        assertThat(result.entities().getFirst().assetItemId()).isEqualTo(31L);
        ArgumentCaptor<AssetItem> itemCaptor = ArgumentCaptor.forClass(AssetItem.class);
        verify(assetService).createItem(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).extracting(AssetItem::getAssetId, AssetItem::getItemType,
                AssetItem::getName, AssetItem::getSourceType)
                .containsExactly(30L, "initial", "撤离列车站台", 2);
    }

    @Test
    void resolveDropsFourthSupportingPropInsteadOfCreatingIt() {
        when(assetService.findOrCreate(any(Asset.class))).thenReturn(created(10L));
        when(assetService.listItems(anyLong()))
                .thenReturn(List.of(AssetItem.builder().id(11L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, manifestWithFourSupportingProps());

        assertThat(result.entities()).filteredOn(entity -> "atmospheric".equals(entity.importance())).hasSize(1);
        verify(assetService, times(3)).findOrCreate(any(Asset.class));
    }

    @Test
    void resolveRejectsBlankOrDuplicateEntityKeys() {
        SceneEntity blank = new SceneEntity(" ", "撤离站台", "scene", "station", "core", false,
                null, null, null);
        SceneEntity duplicate = new SceneEntity("scene:station", "备用站台", "scene", "station", "supporting", false,
                null, null, null);

        assertThatThrownBy(() -> service.resolve(1L, 9L,
                new SceneEntityManifest(1, List.of(blank)))).hasMessageContaining("key is required");
        assertThatThrownBy(() -> service.resolve(1L, 9L,
                new SceneEntityManifest(1, List.of(entity("scene:station", "撤离站台", "scene", "station", "core"), duplicate))))
                .hasMessageContaining("duplicate entity key");
    }

    @Test
    void resolveForcesCoreEntitiesToDefaultForShots() {
        when(assetService.findOrCreate(any(Asset.class))).thenReturn(reused(30L, "撤离列车站台"));
        when(assetService.listItems(30L)).thenReturn(List.of(AssetItem.builder().id(31L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, new SceneEntityManifest(1, List.of(
                new SceneEntity("scene:platform", "撤离列车站台", "scene", "station", "core", false,
                        null, null, "requested"))));

        assertThat(result.entities().getFirst().defaultForShots()).isTrue();
    }

    @Test
    void manifestRoundTripKeepsCollectiveAndResolvedIds() {
        SceneEntity entity = new SceneEntity("character:evacuees", "撤离士兵群",
                "character", "collective", "core", true, 11L, 21L, "auto_created");

        SceneEntityManifest parsed = SceneEntityManifest.fromJson(
                new SceneEntityManifest(1, List.of(entity)).toJson());

        assertThat(parsed.entities()).containsExactly(entity);
    }

    @Test
    void blankJsonReturnsInitialEmptyManifest() {
        SceneEntityManifest manifest = SceneEntityManifest.fromJson("  ");

        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.entities()).isEmpty();
    }

    private static SceneEntityManifest manifestWithCoreCrowdAndTrain() {
        return new SceneEntityManifest(1, List.of(
                entity("character:evacuees", "撤离士兵群", "character", "collective", "core"),
                entity("prop:armored-train", "装甲撤离列车", "prop", "vehicle", "core")));
    }

    private static SceneEntityManifest manifestWithFourSupportingProps() {
        return new SceneEntityManifest(1, List.of(
                entity("prop:1", "道具一", "prop", "vehicle", "supporting"),
                entity("prop:2", "道具二", "prop", "vehicle", "supporting"),
                entity("prop:3", "道具三", "prop", "vehicle", "supporting"),
                entity("prop:4", "道具四", "prop", "vehicle", "supporting")));
    }

    private static SceneEntity entity(String key, String name, String assetType, String entitySubtype,
                                      String importance) {
        return new SceneEntity(key, name, assetType, entitySubtype, importance, "core".equals(importance),
                null, null, "requested");
    }

    private static AssetService.FindOrCreateResult created(Long id) {
        return new AssetService.FindOrCreateResult(Asset.builder().id(id).build(), true);
    }

    private static AssetService.FindOrCreateResult reused(Long id) {
        return reused(id, null);
    }

    private static AssetService.FindOrCreateResult reused(Long id, String name) {
        return new AssetService.FindOrCreateResult(Asset.builder().id(id).name(name).build(), false);
    }
}
