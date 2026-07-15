package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import com.stonewu.fusion.service.asset.model.EpisodeAssetSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Finds explainable, image-backed candidates strictly within one episode and asset type. */
@Service
@RequiredArgsConstructor
public class EpisodeAssetCandidateService {

    private static final int UNIQUE_SCORE = 90;
    private static final int UNIQUE_MARGIN = 15;
    private static final int MIN_SCORE = 60;

    private final AssetService assetService;
    private final AssetNameNormalizer normalizer;

    public EpisodeAssetSearchResult search(Long projectId, Integer episodeNumber, String type, String name) {
        AssetNameNormalizer.NameForms requested = normalizer.forms(name);
        if (requested.base().isBlank()) {
            return none();
        }

        List<EpisodeAssetCandidate> candidates = assetService.listByProjectEpisode(projectId, episodeNumber, type).stream()
                .map(asset -> candidate(asset, requested))
                .filter(candidate -> candidate != null && candidate.score() >= MIN_SCORE)
                .sorted(Comparator.comparingInt(EpisodeAssetCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.asset().getId()))
                .limit(5)
                .toList();
        if (candidates.isEmpty()) {
            return none();
        }

        int best = candidates.getFirst().score();
        int second = candidates.size() > 1 ? candidates.get(1).score() : 0;
        String status = best >= UNIQUE_SCORE && (candidates.size() == 1 || best - second >= UNIQUE_MARGIN)
                ? "unique" : "ambiguous";
        return new EpisodeAssetSearchResult(status, best, candidates);
    }

    /** Backward-compatible list API for callers that only need candidates. */
    public List<EpisodeAssetCandidate> findCandidates(Long projectId, Integer episodeNumber, String type, String name) {
        return search(projectId, episodeNumber, type, name).candidates();
    }

    private EpisodeAssetCandidate candidate(Asset asset, AssetNameNormalizer.NameForms requested) {
        AssetNameNormalizer.NameForms available = normalizer.forms(asset.getName());
        Match match = score(requested, available);
        if (match.score() < MIN_SCORE) {
            return null;
        }
        AssetItem item = assetService.listItems(asset.getId()).stream()
                .filter(candidate -> candidate.getImageUrl() != null && !candidate.getImageUrl().isBlank())
                .findFirst()
                .orElse(null);
        if (item == null) {
            return null;
        }
        return new EpisodeAssetCandidate(asset, match.mode(), match.score(), available.display(),
                match.evidence(), item);
    }

    private Match score(AssetNameNormalizer.NameForms requested, AssetNameNormalizer.NameForms available) {
        if (available.display().equals(requested.display())) {
            return new Match(100, "exact", "清洗后的名称完全相同");
        }
        if (available.base().equals(requested.base())) {
            return new Match(90, "visual_suffix", "去除视觉后缀后相同");
        }
        if (containsEither(available.base(), requested.base())) {
            return new Match(80, "contains", "清洗后的名称存在包含关系");
        }
        int similarity = lcsSimilarity(available.base(), requested.base());
        return similarity >= MIN_SCORE
                ? new Match(Math.min(79, similarity), "character_similarity", "中文字符顺序相似")
                : new Match(0, "none", "名称相似度不足");
    }

    private boolean containsEither(String left, String right) {
        return left.length() >= 2 && right.length() >= 2 && (left.contains(right) || right.contains(left));
    }

    private int lcsSimilarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        int[][] lengths = new int[left.length() + 1][right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                lengths[i][j] = left.charAt(i - 1) == right.charAt(j - 1)
                        ? lengths[i - 1][j - 1] + 1
                        : Math.max(lengths[i - 1][j], lengths[i][j - 1]);
            }
        }
        return 200 * lengths[left.length()][right.length()] / (left.length() + right.length());
    }

    private EpisodeAssetSearchResult none() {
        return new EpisodeAssetSearchResult("none", 0, List.of());
    }

    private record Match(int score, String mode, String evidence) {
    }
}
