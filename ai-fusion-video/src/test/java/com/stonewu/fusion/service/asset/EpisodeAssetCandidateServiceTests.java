package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeAssetCandidateServiceTests {

    @Mock
    private AssetService assetService;

    @Test
    void findsTheCurrentEpisodeAssetAfterRemovingAKnownVisualSuffix() {
        when(assetService.listByProjectEpisode(1L, 1, "character"))
                .thenReturn(List.of(Asset.builder().id(10L).projectId(1L).episodeNumber(1)
                        .type("character").name("凌炽表情图").build()));

        List<EpisodeAssetCandidate> candidates = new EpisodeAssetCandidateService(assetService)
                .findCandidates(1L, 1, "character", "凌炽");

        assertThat(candidates).extracting(candidate -> candidate.asset().getId(), EpisodeAssetCandidate::matchMode)
                .containsExactly(tuple(10L, "suffix_normalized"));
        verify(assetService).listByProjectEpisode(1L, 1, "character");
    }

    @Test
    void prefersAnExactCurrentEpisodeAssetOverASuffixCandidate() {
        when(assetService.listByProjectEpisode(1L, 1, "character"))
                .thenReturn(List.of(
                        Asset.builder().id(10L).name("凌炽表情图").build(),
                        Asset.builder().id(11L).name("凌炽").build()));

        List<EpisodeAssetCandidate> candidates = new EpisodeAssetCandidateService(assetService)
                .findCandidates(1L, 1, "character", "凌炽");

        assertThat(candidates).extracting(candidate -> candidate.asset().getId(), EpisodeAssetCandidate::matchMode)
                .containsExactly(tuple(11L, "exact"));
    }

    @Test
    void returnsEveryBestCandidateSoCallersCanTreatTheMatchAsAmbiguous() {
        when(assetService.listByProjectEpisode(1L, 1, "character"))
                .thenReturn(List.of(
                        Asset.builder().id(10L).name("凌炽表情图").build(),
                        Asset.builder().id(11L).name("凌炽三视图").build()));

        List<EpisodeAssetCandidate> candidates = new EpisodeAssetCandidateService(assetService)
                .findCandidates(1L, 1, "character", "凌炽");

        assertThat(candidates).extracting(candidate -> candidate.asset().getId(), EpisodeAssetCandidate::matchMode)
                .containsExactlyInAnyOrder(tuple(10L, "suffix_normalized"), tuple(11L, "suffix_normalized"));
    }
}
