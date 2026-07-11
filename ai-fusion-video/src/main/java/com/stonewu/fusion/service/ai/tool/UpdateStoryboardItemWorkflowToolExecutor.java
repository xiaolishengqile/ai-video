package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.controller.storyboard.vo.StoryboardWorkflowUpdateReqVO;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 更新分镜条目视频工作流工具。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateStoryboardItemWorkflowToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;

    @Override
    public String getToolName() {
        return "update_storyboard_item_workflow";
    }

    @Override
    public String getDisplayName() {
        return "更新分镜视频工作流";
    }

    @Override
    public String getToolDescription() {
        return """
                保存分镜镜头的视频工作流素材与模式信息。
                可用于写入自动/剧情/战斗模式、25宫格剧情故事板、动作故事板、关键帧、提示词模式和质检结果。
                首尾帧仍使用 update_storyboard_item_frame，此工具不要写首尾帧字段。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "storyboardItemId": {"type": "integer", "description": "分镜条目ID"},
                    "videoWorkflowMode": {"type": "string", "enum": ["auto", "narrative", "action"], "description": "用户选择的视频工作流模式"},
                    "videoWorkflowResolvedMode": {"type": "string", "enum": ["narrative", "action"], "description": "自动判断或本次实际采用的模式"},
                    "videoWorkflowReason": {"type": "string", "description": "模式判断原因"},
                    "storyboardImageUrl": {"type": "string", "description": "故事板图URL"},
                    "grid25ImageUrl": {"type": "string", "description": "25宫格剧情故事板图URL，仅剧情模式使用"},
                    "grid25Prompt": {"type": "string", "description": "25宫格剧情故事板提示词"},
                    "actionStoryboardImageUrl": {"type": "string", "description": "动作故事板图URL，仅战斗模式使用"},
                    "actionStoryboardPrompt": {"type": "string", "description": "动作故事板提示词"},
                    "motionPlan": {"type": "string", "description": "身位调度与动作规划"},
                    "keyFrameImageUrls": {"type": "array", "items": {"type": "string"}, "description": "关键帧URL数组"},
                    "videoPromptMode": {"type": "string", "enum": ["narrative", "action"], "description": "视频提示词采用的模式"},
                    "qualityCheckStatus": {"type": "integer", "enum": [0, 1, 2, 3], "description": "质检状态：0未质检 1质检中 2通过 3失败"},
                    "qualityCheckResult": {"type": "string", "description": "质检结果"}
                  },
                  "required": ["storyboardItemId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long itemId = params.getLong("storyboardItemId");
            if (itemId == null) {
                return errorResult("缺少 storyboardItemId");
            }

            StoryboardWorkflowUpdateReqVO reqVO = new StoryboardWorkflowUpdateReqVO();
            reqVO.setVideoWorkflowMode(params.getStr("videoWorkflowMode"));
            reqVO.setVideoWorkflowResolvedMode(params.getStr("videoWorkflowResolvedMode"));
            reqVO.setVideoWorkflowReason(params.getStr("videoWorkflowReason"));
            reqVO.setStoryboardImageUrl(params.getStr("storyboardImageUrl"));
            reqVO.setGrid25ImageUrl(params.getStr("grid25ImageUrl"));
            reqVO.setGrid25Prompt(params.getStr("grid25Prompt"));
            reqVO.setActionStoryboardImageUrl(params.getStr("actionStoryboardImageUrl"));
            reqVO.setActionStoryboardPrompt(params.getStr("actionStoryboardPrompt"));
            reqVO.setMotionPlan(params.getStr("motionPlan"));
            reqVO.setVideoPromptMode(params.getStr("videoPromptMode"));
            reqVO.setQualityCheckStatus(params.getInt("qualityCheckStatus"));
            reqVO.setQualityCheckResult(params.getStr("qualityCheckResult"));

            if (params.containsKey("keyFrameImageUrls")) {
                if (params.get("keyFrameImageUrls") instanceof cn.hutool.json.JSONArray arr) {
                    reqVO.setKeyFrameImageUrls(arr.toString());
                } else {
                    reqVO.setKeyFrameImageUrls(params.getStr("keyFrameImageUrls"));
                }
            }

            StoryboardItem updated = storyboardService.updateItemWorkflow(itemId, reqVO);
            log.info("[update_storyboard_item_workflow] 已更新分镜视频工作流: itemId={}, mode={}, resolved={}",
                    itemId, updated.getVideoWorkflowMode(), updated.getVideoWorkflowResolvedMode());

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardItemId", itemId)
                    .set("videoWorkflowMode", updated.getVideoWorkflowMode())
                    .set("videoWorkflowResolvedMode", updated.getVideoWorkflowResolvedMode())
                    .set("hasGrid25", StrUtil.isNotBlank(updated.getGrid25ImageUrl()))
                    .set("hasActionStoryboard", StrUtil.isNotBlank(updated.getActionStoryboardImageUrl()))
                    .set("hasKeyFrames", StrUtil.isNotBlank(updated.getKeyFrameImageUrls()))
                    .set("message", "分镜视频工作流已保存")
                    .toString();
        } catch (Exception e) {
            log.error("[update_storyboard_item_workflow] 更新失败", e);
            return errorResult("更新失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
