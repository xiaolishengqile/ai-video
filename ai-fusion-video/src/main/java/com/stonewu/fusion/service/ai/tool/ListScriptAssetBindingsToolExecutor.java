package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptAssetPrebindingService;
import com.stonewu.fusion.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Lists reviewed and pending asset prebindings for one script episode. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListScriptAssetBindingsToolExecutor implements ToolExecutor {

    private final ScriptAssetPrebindingService prebindingService;
    private final ScriptService scriptService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "list_script_asset_bindings";
    }

    @Override
    public String getDisplayName() {
        return "查询资产剧本预匹配";
    }

    @Override
    public String getToolDescription() {
        return "查询指定剧本分集的资产预匹配结果，供场次解析优先复用用户上传资产。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "scriptEpisodeId": { "type": "integer" }
                  },
                  "required": ["scriptEpisodeId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            var params = JSONUtil.parseObj(toolInput);
            Long scriptEpisodeId = params.getLong("scriptEpisodeId");
            if (scriptEpisodeId == null) return error("缺少 scriptEpisodeId");
            var episode = scriptService.getEpisodeById(scriptEpisodeId);
            var script = scriptService.getById(episode.getScriptId());
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(script.getProjectId(), context.getUserId())) {
                return error("无权访问该项目");
            }
            JSONArray rows = new JSONArray();
            for (ScriptAssetBinding binding : prebindingService.listBindings(scriptEpisodeId)) {
                rows.add(JSONUtil.createObj()
                        .set("id", binding.getId())
                        .set("entityName", binding.getEntityName())
                        .set("assetType", binding.getAssetType())
                        .set("assetId", binding.getAssetId())
                        .set("assetItemId", binding.getAssetItemId())
                        .set("matchStatus", binding.getMatchStatus())
                        .set("matchSource", binding.getMatchSource())
                        .set("confidence", binding.getConfidence())
                        .set("reviewed", binding.getReviewed()));
            }
            return JSONUtil.createObj().set("status", "success").set("bindings", rows).toString();
        } catch (Exception e) {
            log.warn("查询资产剧本预匹配失败", e);
            return error("查询失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
