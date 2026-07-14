package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetCatalogSnapshotService;
import com.stonewu.fusion.service.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads a previously captured project asset catalog after checking project access. */
@Component
@RequiredArgsConstructor
public class GetProjectAssetCatalogSnapshotToolExecutor implements ToolExecutor {

    private final AssetCatalogSnapshotService snapshotService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "get_project_asset_catalog_snapshot";
    }

    @Override
    public String getDisplayName() {
        return "读取资产目录快照";
    }

    @Override
    public String getToolDescription() {
        return "读取主 Agent 指定的不可变项目资产目录快照，供场次或分镜解析绑定资产。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {"type":"object","properties":{"snapshotId":{"type":"integer"}},"required":["snapshotId"]}
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        Long snapshotId = JSONUtil.parseObj(toolInput).getLong("snapshotId");
        if (snapshotId == null) {
            return error("缺少 snapshotId");
        }
        AssetCatalogSnapshot snapshot = snapshotService.getById(snapshotId);
        if (!projectService.canAccessProject(snapshot.getProjectId(), context.getUserId())) {
            return error("无权访问该项目");
        }
        return JSONUtil.createObj()
                .set("snapshotId", snapshot.getId())
                .set("projectId", snapshot.getProjectId())
                .set("scriptId", snapshot.getScriptId())
                .set("scriptEpisodeId", snapshot.getScriptEpisodeId())
                .set("assetCount", snapshot.getAssetCount())
                .set("assets", JSONUtil.parseArray(snapshot.getCatalogJson()))
                .toString();
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
