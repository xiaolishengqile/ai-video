package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.service.asset.model.AssetFolderImportFile;
import com.stonewu.fusion.service.asset.model.AssetFolderImportPreviewItem;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 根据用户已标注的文件名生成文件夹导入候选项。
 * <p>
 * 本服务只识别明确的变体后缀，不查询数据库，也不尝试模糊匹配父资产。所有已识别
 * 的后缀均先标记为 {@code variant_candidate}，由后续导入服务结合批次和项目资产
 * 决定它最终是子资产还是独立资产。
 */
@Service
public class AssetFolderImportNameService {

    private static final Map<String, String> ITEM_TYPES = Map.ofEntries(
            Map.entry("正面", "front"),
            Map.entry("侧面", "side"),
            Map.entry("背面", "back"),
            Map.entry("细节", "detail"),
            Map.entry("表情图", "expression"),
            Map.entry("三视图", "variant"),
            Map.entry("多机位", "variant"),
            Map.entry("六面展开图", "variant"),
            Map.entry("内景", "variant"),
            Map.entry("外景", "variant"),
            Map.entry("夜景", "variant"),
            Map.entry("战斗形态", "variant"),
            Map.entry("战斗服", "variant")
    );

    private static final List<String> KNOWN_SUFFIXES = ITEM_TYPES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    public List<AssetFolderImportPreviewItem> preview(List<AssetFolderImportFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream().map(this::classify).toList();
    }

    private AssetFolderImportPreviewItem classify(AssetFolderImportFile file) {
        String stem = normalizedStem(file == null ? null : file.originalName());
        VariantMatch match = splitKnownVariant(stem);
        String relativePath = file == null ? null : file.relativePath();
        String originalName = file == null ? null : file.originalName();

        if (match == null) {
            return new AssetFolderImportPreviewItem(relativePath, originalName, stem, null, "initial", "root");
        }
        return new AssetFolderImportPreviewItem(
                relativePath,
                originalName,
                match.assetName(),
                match.variantName(),
                ITEM_TYPES.get(match.variantName()),
                "variant_candidate");
    }

    private String normalizedStem(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        int extensionStart = name.lastIndexOf('.');
        if (extensionStart > 0) {
            name = name.substring(0, extensionStart);
        }
        return name.replaceFirst("(?i)^[a-z]+-\\d+[\\s_-]*", "").trim();
    }

    private VariantMatch splitKnownVariant(String stem) {
        for (String suffix : KNOWN_SUFFIXES) {
            if (!stem.endsWith(suffix)) {
                continue;
            }
            String assetName = stem.substring(0, stem.length() - suffix.length()).trim();
            if (!assetName.isEmpty()) {
                return new VariantMatch(assetName, suffix);
            }
        }
        return null;
    }

    private record VariantMatch(String assetName, String variantName) {
    }
}
