package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import com.stonewu.fusion.service.asset.model.EpisodeAssetSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeAssetCandidateServiceTests {

    @Mock
    private AssetService assetService;

    @Test
    void findsPathPrefixedAssetAsUniqueAfterNormalization() {
        when(assetService.listByProjectEpisode(1L, 1, "character"))
                .thenReturn(List.of(Asset.builder().id(10L).projectId(1L).episodeNumber(1)
                        .type("character").name("角色图/第一集/凌炽表情图").build()));
        image(10L, 101L);

        EpisodeAssetSearchResult result = service().search(1L, 1, "character", "凌炽");

        assertThat(result.matchStatus()).isEqualTo("unique");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.asset().getId()).isEqualTo(10L);
            assertThat(candidate.score()).isEqualTo(90);
            assertThat(candidate.matchMode()).isEqualTo("visual_suffix");
            assertThat(candidate.assetItem().getId()).isEqualTo(101L);
        });
    }

    @Test
    void returnsImportedExactLeafAsUnique() {
        when(assetService.listByProjectEpisode(1L, 13, "prop"))
                .thenReturn(List.of(
                        Asset.builder().id(11L).name("道具图/第十三集道具图/半民用搬运机甲").build()));
        image(11L, 111L);

        EpisodeAssetSearchResult result = service().search(1L, 13, "prop", "半民用搬运机甲");

        assertThat(result.matchStatus()).isEqualTo("unique");
        assertThat(result.candidates()).singleElement().extracting(EpisodeAssetCandidate::score).isEqualTo(100);
    }

    @Test
    void keepsSeveralCharacterStatesAmbiguous() {
        when(assetService.listByProjectEpisode(1L, 13, "character"))
                .thenReturn(List.of(
                        Asset.builder().id(20L).name("角色图/第十三集/凌烬地表过滤面罩状态设定图").build(),
                        Asset.builder().id(21L).name("角色图/第十三集/凌烬脱掉过滤面罩状态设定图").build()));
        image(20L, 201L);
        image(21L, 211L);

        EpisodeAssetSearchResult result = service().search(1L, 13, "character", "凌烬");

        assertThat(result.matchStatus()).isEqualTo("ambiguous");
        assertThat(result.candidates()).hasSize(2).allSatisfy(candidate -> assertThat(candidate.score()).isEqualTo(80));
    }

    @Test
    void recallsGreySpineCoreAsAmbiguousFuzzyCandidate() {
        when(assetService.listByProjectEpisode(1L, 13, "prop"))
                .thenReturn(List.of(Asset.builder().id(30L)
                        .name("道具图/第十三集道具图/灰脊”蓝灰防御核心").build()));
        image(30L, 301L);

        EpisodeAssetSearchResult result = service().search(1L, 13, "prop", "灰脊核心");

        assertThat(result.matchStatus()).isEqualTo("ambiguous");
        assertThat(result.bestScore()).isBetween(60, 79);
    }

    @Test
    void excludesCandidatesWithoutUsableImages() {
        when(assetService.listByProjectEpisode(1L, 13, "prop"))
                .thenReturn(List.of(Asset.builder().id(40L).name("地下异常热源").build()));
        when(assetService.listItems(40L)).thenReturn(List.of(AssetItem.builder().id(401L).imageUrl(" ").build()));

        EpisodeAssetSearchResult result = service().search(1L, 13, "prop", "地下异常热源");

        assertThat(result.matchStatus()).isEqualTo("none");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void returnsNoneWhenCurrentEpisodeHasNoAssetOfRequestedType() {
        when(assetService.listByProjectEpisode(1L, 13, "scene")).thenReturn(List.of());

        EpisodeAssetSearchResult result = service().search(1L, 13, "scene", "补给站");

        assertThat(result.matchStatus()).isEqualTo("none");
    }

    private EpisodeAssetCandidateService service() {
        return new EpisodeAssetCandidateService(assetService, new AssetNameNormalizer());
    }

    private void image(Long assetId, Long itemId) {
        when(assetService.listItems(assetId)).thenReturn(List.of(
                AssetItem.builder().id(itemId).imageUrl("/media/" + itemId + ".png").build()));
    }
}
