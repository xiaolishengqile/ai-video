package com.stonewu.fusion.service.asset.model;

import com.stonewu.fusion.entity.asset.Asset;

/** A same-episode asset candidate with an explainable text-name match mode. */
public record EpisodeAssetCandidate(Asset asset, String matchMode) {
}
