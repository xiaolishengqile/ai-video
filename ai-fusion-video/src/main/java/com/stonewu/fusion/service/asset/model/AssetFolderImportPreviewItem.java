package com.stonewu.fusion.service.asset.model;

/**
 * 文件名分类结果。variant_candidate 是否能归入父资产由导入服务进一步解析。
 */
public record AssetFolderImportPreviewItem(
        String relativePath,
        String originalName,
        String assetName,
        String variantName,
        String itemType,
        String kind
) {
}
