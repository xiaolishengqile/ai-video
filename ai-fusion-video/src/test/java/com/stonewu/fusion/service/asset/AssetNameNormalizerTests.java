package com.stonewu.fusion.service.asset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetNameNormalizerTests {

    private final AssetNameNormalizer normalizer = new AssetNameNormalizer();

    @Test
    void keepsMeaningfulNameWhileRemovingImportPathAndVisualSuffix() {
        AssetNameNormalizer.NameForms forms = normalizer.forms(
                "道具图/第十三集道具图/半民用搬运机甲 设定图");

        assertThat(forms.display()).isEqualTo("半民用搬运机甲设定图");
        assertThat(forms.base()).isEqualTo("半民用搬运机甲");
    }

    @Test
    void keepsVerticalBarContentBecauseItCanCarryIdentity() {
        AssetNameNormalizer.NameForms forms = normalizer.forms("道具图/灰脊｜旧防御机甲设定图");

        assertThat(forms.display()).isEqualTo("灰脊旧防御机甲设定图");
        assertThat(forms.base()).isEqualTo("灰脊旧防御机甲");
    }
}
