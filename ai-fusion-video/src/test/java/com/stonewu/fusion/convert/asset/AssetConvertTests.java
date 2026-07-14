package com.stonewu.fusion.convert.asset;

import com.stonewu.fusion.controller.asset.vo.AssetCreateReqVO;
import com.stonewu.fusion.controller.asset.vo.AssetUpdateReqVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetConvertTests {

    @Test
    void mapsEpisodeNumberWhenCreatingOrMigratingAnAsset() {
        AssetCreateReqVO create = new AssetCreateReqVO();
        create.setProjectId(1L);
        create.setEpisodeNumber(2);
        create.setType("character");
        create.setName("凌炽");
        AssetUpdateReqVO update = new AssetUpdateReqVO();
        update.setId(9L);
        update.setEpisodeNumber(3);

        assertThat(AssetConvert.INSTANCE.convert(create).getEpisodeNumber()).isEqualTo(2);
        assertThat(AssetConvert.INSTANCE.convert(update).getEpisodeNumber()).isEqualTo(3);
    }
}
