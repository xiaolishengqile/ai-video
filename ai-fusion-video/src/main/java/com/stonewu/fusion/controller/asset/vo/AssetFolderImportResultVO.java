package com.stonewu.fusion.controller.asset.vo;

import java.util.List;

public record AssetFolderImportResultVO(List<Item> results) {
    public record Item(
            String relativePath,
            String originalName,
            String status,
            String assetName,
            String variantName,
            Integer episodeNumber,
            String reason) {
    }
}
