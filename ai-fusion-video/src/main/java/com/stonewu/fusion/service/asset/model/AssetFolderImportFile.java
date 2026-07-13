package com.stonewu.fusion.service.asset.model;

/**
 * 文件夹导入预览所需的文件元数据。
 */
public record AssetFolderImportFile(
        String relativePath,
        String originalName
) {
}
