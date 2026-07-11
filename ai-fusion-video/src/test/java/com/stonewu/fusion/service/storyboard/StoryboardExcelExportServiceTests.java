package com.stonewu.fusion.service.storyboard;

import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardExcelExportServiceTests {

    @Test
    void collectImageAssetsIncludesStoryboardFramesGridActionAndKeyFrames() {
        StoryboardItem item = StoryboardItem.builder()
                .id(11L)
                .shotNumber("3")
                .storyboardImageUrl("/media/images/storyboard.png")
                .grid25ImageUrl("/media/images/grid25.png")
                .grid25ReferenceImageUrls("[\"/media/images/grid-ref-1.png\",\"/media/images/grid-ref-2.png\"]")
                .actionStoryboardImageUrl("/media/images/action.png")
                .firstFrameImageUrl("/media/images/first.png")
                .lastFrameImageUrl("/media/images/last.png")
                .keyFrameImageUrls("[\"/media/images/key-1.png\",\"/media/images/key-2.png\"]")
                .build();

        List<StoryboardExcelExportService.ExportImageAsset> assets =
                StoryboardExcelExportService.collectImageAssets(item);

        assertThat(assets)
                .extracting(StoryboardExcelExportService.ExportImageAsset::label)
                .containsExactly("故事板图", "25宫格图", "25宫格参考图1", "25宫格参考图2", "动作故事板图", "首帧", "尾帧", "关键帧1", "关键帧2");
        assertThat(assets)
                .extracting(StoryboardExcelExportService.ExportImageAsset::url)
                .containsExactly(
                        "/media/images/storyboard.png",
                        "/media/images/grid25.png",
                        "/media/images/grid-ref-1.png",
                        "/media/images/grid-ref-2.png",
                        "/media/images/action.png",
                        "/media/images/first.png",
                        "/media/images/last.png",
                        "/media/images/key-1.png",
                        "/media/images/key-2.png"
                );
    }
}
