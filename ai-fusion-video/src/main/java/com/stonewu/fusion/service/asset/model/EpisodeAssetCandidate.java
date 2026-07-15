package com.stonewu.fusion.service.asset.model;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;

/** A same-episode asset candidate with an explainable text-name match mode. */
public record EpisodeAssetCandidate(
        Asset asset,
        String matchMode,
        int score,
        String matchedName,
        String evidence,
        AssetItem assetItem) {

    public EpisodeAssetCandidate(Asset asset, String matchMode) {
        this(asset, matchMode, 0, asset == null ? null : asset.getName(), null, null);
    }
}
