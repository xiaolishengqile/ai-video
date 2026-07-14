package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Finds only the highest-confidence text-name matches within one episode. */
@Service
@RequiredArgsConstructor
public class EpisodeAssetCandidateService {

    private static final List<String> VISUAL_SUFFIXES = List.of(
            "六面展开图", "表情图", "三视图", "多机位", "战斗形态", "战斗服", "内景", "外景", "夜景",
            "正面", "侧面", "背面", "细节");

    private final AssetService assetService;

    public List<EpisodeAssetCandidate> findCandidates(Long projectId, Integer episodeNumber, String type, String name) {
        String requested = AssetService.normalizeName(name);
        if (requested.isBlank()) {
            return List.of();
        }
        return assetService.listByProjectEpisode(projectId, episodeNumber, type).stream()
                .map(asset -> candidate(asset, requested))
                .filter(candidate -> candidate != null)
                .collect(java.util.stream.Collectors.groupingBy(candidate -> score(candidate.matchMode())))
                .entrySet().stream()
                .max(java.util.Map.Entry.comparingByKey())
                .map(java.util.Map.Entry::getValue)
                .orElseGet(List::of).stream()
                .sorted(Comparator.comparing(candidate -> candidate.asset().getId()))
                .limit(5)
                .toList();
    }

    private EpisodeAssetCandidate candidate(Asset asset, String requested) {
        String assetName = AssetService.normalizeName(asset.getName());
        if (assetName.equals(requested)) {
            return new EpisodeAssetCandidate(asset, "exact");
        }
        if (canonicalName(assetName).equals(canonicalName(requested))) {
            return new EpisodeAssetCandidate(asset, "suffix_normalized");
        }
        return null;
    }

    private String canonicalName(String normalizedName) {
        for (String suffix : VISUAL_SUFFIXES) {
            if (normalizedName.endsWith(suffix) && normalizedName.length() > suffix.length()) {
                return normalizedName.substring(0, normalizedName.length() - suffix.length());
            }
        }
        return normalizedName;
    }

    private int score(String matchMode) {
        return "exact".equals(matchMode) ? 2 : 1;
    }
}
