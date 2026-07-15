package com.stonewu.fusion.service.asset.model;

import java.util.List;

/** Explainable decision for one episode-local asset search. */
public record EpisodeAssetSearchResult(
        String matchStatus,
        int bestScore,
        List<EpisodeAssetCandidate> candidates) {
}
