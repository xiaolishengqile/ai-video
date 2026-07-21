package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 分镜宫格图直连生成提交结果。
 */
@Schema(description = "分镜宫格图直连生成提交结果")
@Data
@Builder
public class StoryboardGridImageGenerateRespVO {

    private int totalCount;

    private int submittedCount;

    private int skippedGeneratedCount;

    private int skippedMissingModeCount;

    private int skippedMissingPromptCount;

    private int skippedRunningCount;

    private String message;
}
