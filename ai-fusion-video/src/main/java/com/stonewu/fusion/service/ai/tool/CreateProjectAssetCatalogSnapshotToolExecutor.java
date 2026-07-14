package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetCatalogSnapshotService;
import com.stonewu.fusion.service.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Captures the exact project asset catalog used by downstream AI agents. */
@Component
@RequiredArgsConstructor
public class CreateProjectAssetCatalogSnapshotToolExecutor implements ToolExecutor {

    private final AssetCatalogSnapshotService snapshotService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "create_project_asset_catalog_snapshot";
    }

    @Override
    public String getDisplayName() {
        return "创建资产目录快照";
    }

    @Override
    public String getToolDescription() {
        return "将项目当前的主资产和子资产固化为可传给子 Agent 的资产目录快照。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {"type":"object","properties":{"projectId":{"type":"integer"},"scriptId":{"type":"integer"}},"required":["projectId"]}
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        JSONObject params = JSONUtil.parseObj(toolInput);
        Long projectId = params.getLong("projectId");
        if (projectId == null) {
            return error("缺少 projectId");
        }
        if (!projectService.canAccessProject(projectId, context.getUserId())) {
            return error("无权访问该项目");
        }
        AssetCatalogSnapshot snapshot = snapshotService.create(projectId, params.getLong("scriptId"));
        return JSONUtil.createObj()
                .set("snapshotId", snapshot.getId())
                .set("projectId", snapshot.getProjectId())
                .set("scriptId", snapshot.getScriptId())
                .set("assetCount", snapshot.getAssetCount())
                .toString();
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
