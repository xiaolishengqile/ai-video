package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptAssetPrebindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Runs deterministic asset-to-script prebinding for one script episode. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunScriptAssetPrebindingToolExecutor implements ToolExecutor {

    private final ScriptAssetPrebindingService prebindingService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "run_script_asset_prebinding";
    }

    @Override
    public String getDisplayName() {
        return "运行资产剧本预匹配";
    }

    @Override
    public String getToolDescription() {
        return "在正式场次解析前，按当前剧集扫描用户上传资产与剧本文本的确定性匹配结果；不创建资产。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectId": { "type": "integer" },
                    "scriptId": { "type": "integer" },
                    "scriptEpisodeId": { "type": "integer" }
                  },
                  "required": ["projectId", "scriptId", "scriptEpisodeId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            var params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            Long scriptId = params.getLong("scriptId");
            Long scriptEpisodeId = params.getLong("scriptEpisodeId");
            if (projectId == null || scriptId == null || scriptEpisodeId == null) {
                return error("缺少 projectId、scriptId 或 scriptEpisodeId");
            }
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(projectId, context.getUserId())) {
                return error("无权访问该项目");
            }
            var summary = prebindingService.runEpisodePrebinding(projectId, scriptId, scriptEpisodeId);
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("matched", summary.matched())
                    .set("suggested", summary.suggested())
                    .set("ambiguous", summary.ambiguous())
                    .set("unmatched", summary.unmatched())
                    .set("uploadedUnused", summary.uploadedUnused())
                    .toString();
        } catch (Exception e) {
            log.warn("运行资产剧本预匹配失败", e);
            return error("运行失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
