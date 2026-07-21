package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 分镜宫格图直连生成请求。
 */
@Schema(description = "分镜宫格图直连生成请求")
@Data
public class StoryboardGridImageGenerateReqVO {

    @Schema(description = "指定镜头 ID；为空时处理整张分镜表")
    private List<Long> storyboardItemIds;

    @Schema(description = "是否强制重新生成已有图片")
    private Boolean force;

    @Schema(description = "按镜头传入的临时参考图 URL")
    private Map<Long, List<String>> referenceImageUrlsByItemId;
}
