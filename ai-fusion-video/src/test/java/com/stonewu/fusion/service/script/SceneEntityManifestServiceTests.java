package com.stonewu.fusion.service.script;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneEntityManifestServiceTests {

    @Mock
    private AssetService assetService;

    @InjectMocks
    private SceneEntityManifestService service;

    @Test
    void resolveMatchesOnlyAssetsFromTheCurrentEpisode() {
        Asset asset = Asset.builder().id(10L).episodeNumber(2).name("装甲撤离列车").build();
        when(assetService.findByProjectEpisodeTypeAndName(1L, 2, "prop", "装甲撤离列车"))
                .thenReturn(asset);
        when(assetService.listItems(10L)).thenReturn(List.of(AssetItem.builder().id(11L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, 2,
                new SceneEntityManifest(1, List.of(entity("prop:armored-train", "装甲撤离列车", "prop", "vehicle", "core"))));

        assertThat(result.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.assetId()).isEqualTo(10L);
            assertThat(entity.assetItemId()).isEqualTo(11L);
            assertThat(entity.source()).isEqualTo("matched");
        });
        verify(assetService, never()).findOrCreate(any(Asset.class));
    }

    @Test
    void resolveLeavesMissingAssetsUnmatchedInsteadOfCreatingThem() {
        SceneEntityManifest result = service.resolve(1L, 9L, 2,
                new SceneEntityManifest(1, List.of(entity("scene:platform", "撤离列车站台", "scene", "station", "core"))));

        assertThat(result.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.assetId()).isNull();
            assertThat(entity.assetItemId()).isNull();
            assertThat(entity.source()).isEqualTo("unmatched_episode_catalog");
        });
        verify(assetService).findByProjectEpisodeTypeAndName(1L, 2, "scene", "撤离列车站台");
        verify(assetService, never()).findOrCreate(any(Asset.class));
        verify(assetService, never()).createItem(any(AssetItem.class));
    }

    @Test
    void resolveDropsFourthSupportingPropInsteadOfResolvingIt() {
        when(assetService.findByProjectEpisodeTypeAndName(eq(1L), eq(2), eq("prop"), any()))
                .thenReturn(Asset.builder().id(10L).build());
        when(assetService.listItems(10L)).thenReturn(List.of(AssetItem.builder().id(11L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, 2, manifestWithFourSupportingProps());

        assertThat(result.entities()).filteredOn(entity -> "atmospheric".equals(entity.importance())).hasSize(1);
        verify(assetService, never()).findOrCreate(any(Asset.class));
    }

    @Test
    void resolveRejectsBlankOrDuplicateEntityKeys() {
        SceneEntity blank = new SceneEntity(" ", "撤离站台", "scene", "station", "core", false,
                null, null, null);
        SceneEntity duplicate = new SceneEntity("scene:station", "备用站台", "scene", "station", "supporting", false,
                null, null, null);

        assertThatThrownBy(() -> service.resolve(1L, 9L, 2,
                new SceneEntityManifest(1, List.of(blank)))).hasMessageContaining("key is required");
        assertThatThrownBy(() -> service.resolve(1L, 9L, 2,
                new SceneEntityManifest(1, List.of(entity("scene:station", "撤离站台", "scene", "station", "core"), duplicate))))
                .hasMessageContaining("duplicate entity key");
    }

    @Test
    void resolveForcesMatchedCoreEntitiesToDefaultForShots() {
        when(assetService.findByProjectEpisodeTypeAndName(1L, 2, "scene", "撤离列车站台"))
                .thenReturn(Asset.builder().id(30L).name("撤离列车站台").build());
        when(assetService.listItems(30L)).thenReturn(List.of(AssetItem.builder().id(31L).itemType("initial").build()));

        SceneEntityManifest result = service.resolve(1L, 9L, 2, new SceneEntityManifest(1, List.of(
                new SceneEntity("scene:platform", "撤离列车站台", "scene", "station", "core", false,
                        null, null, "requested"))));

        assertThat(result.entities().getFirst().defaultForShots()).isTrue();
    }

    @Test
    void manifestRoundTripKeepsCollectiveAndResolvedIds() {
        SceneEntity entity = new SceneEntity("character:evacuees", "撤离士兵群",
                "character", "collective", "core", true, 11L, 21L, "matched");

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
}
