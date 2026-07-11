package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分镜 Excel 导出请求。
 */
@Schema(description = "分镜 Excel 导出请求")
@Data
public class StoryboardExcelExportReqVO {

    /** 分镜集ID，不传则导出整个分镜 */
    private Long episodeId;

    /** 分镜场次ID，不传则按分镜集或整个分镜导出 */
    private Long sceneId;
}
