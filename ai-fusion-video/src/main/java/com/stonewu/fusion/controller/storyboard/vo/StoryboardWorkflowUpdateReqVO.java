package com.stonewu.fusion.controller.storyboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 更新分镜条目视频工作流请求。
 */
@Schema(description = "更新分镜条目视频工作流请求")
@Data
public class StoryboardWorkflowUpdateReqVO {

    private String videoWorkflowMode;

    private String videoWorkflowResolvedMode;

    private String videoWorkflowReason;

    private String storyboardImageUrl;

    private String grid25ImageUrl;

    private String grid25Prompt;

    private String grid25ReferenceImageUrls;

    private String actionStoryboardImageUrl;

    private String actionStoryboardPrompt;

    private String motionPlan;

    private String keyFrameImageUrls;

    private String videoPromptMode;

    private Integer qualityCheckStatus;

    private String qualityCheckResult;
}
