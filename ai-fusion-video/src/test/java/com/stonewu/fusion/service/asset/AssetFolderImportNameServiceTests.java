package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.service.asset.model.AssetFolderImportFile;
import com.stonewu.fusion.service.asset.model.AssetFolderImportPreviewItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetFolderImportNameServiceTests {

    private final AssetFolderImportNameService service = new AssetFolderImportNameService();

    @Test
    void classifiesKnownSuffixesIntoVariantCandidates() {
        List<AssetFolderImportPreviewItem> result = service.preview(List.of(
                file("A-15 地表实训发布厅.png"),
                file("A-15 地表实训发布厅多机位.png"),
                file("秦炽川.png"),
                file("秦炽川 三视图.png"),
                file("秦炽川便携核心诊断仪.png")));

        assertThat(result).extracting(AssetFolderImportPreviewItem::assetName)
                .containsExactly("地表实训发布厅", "地表实训发布厅", "秦炽川", "秦炽川", "秦炽川便携核心诊断仪");
        assertThat(result).extracting(AssetFolderImportPreviewItem::variantName)
                .containsExactly(null, "多机位", null, "三视图", null);
        assertThat(result).extracting(AssetFolderImportPreviewItem::kind)
                .containsExactly("root", "variant_candidate", "root", "variant_candidate", "root");
    }

    @Test
    void preservesUnpairedKnownSuffixAsVariantCandidateForLaterParentResolution() {
        AssetFolderImportPreviewItem result = service.preview(List.of(file("P-01 林澈战斗服.png"))).getFirst();

        assertThat(result.assetName()).isEqualTo("林澈");
        assertThat(result.variantName()).isEqualTo("战斗服");
        assertThat(result.itemType()).isEqualTo("variant");
        assertThat(result.kind()).isEqualTo("variant_candidate");
    }

    @Test
    void mapsSpecificVariantSuffixesToExistingItemTypesAndUsesLongestSuffix() {
        List<AssetFolderImportPreviewItem> result = service.preview(List.of(
                file("角色 正面.png"),
                file("角色 侧面.png"),
                file("角色 背面.png"),
                file("角色 细节.png"),
                file("角色 表情图.png"),
                file("场景 六面展开图.png")));

        assertThat(result).extracting(AssetFolderImportPreviewItem::itemType)
                .containsExactly("front", "side", "back", "detail", "expression", "variant");
        assertThat(result.getLast().assetName()).isEqualTo("场景");
        assertThat(result.getLast().variantName()).isEqualTo("六面展开图");
    }

    @Test
    void removesOnlyLeadingCodePrefixAndExtension() {
        AssetFolderImportPreviewItem result = service.preview(List.of(
                new AssetFolderImportFile("nested/B-01 联邦安全部冷白会议室.png", "B-01 联邦安全部冷白会议室.png")))
                .getFirst();

        assertThat(result.relativePath()).isEqualTo("nested/B-01 联邦安全部冷白会议室.png");
        assertThat(result.originalName()).isEqualTo("B-01 联邦安全部冷白会议室.png");
        assertThat(result.assetName()).isEqualTo("联邦安全部冷白会议室");
        assertThat(result.variantName()).isNull();
    }

    private static AssetFolderImportFile file(String name) {
        return new AssetFolderImportFile(name, name);
    }
}
