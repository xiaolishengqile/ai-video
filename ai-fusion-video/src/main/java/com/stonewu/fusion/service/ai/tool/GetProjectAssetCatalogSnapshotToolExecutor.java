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
        return "读取当前项目、剧本和剧本分集指定的不可变资产目录快照，供分镜绑定资产。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {"type":"object","properties":{"snapshotId":{"type":"integer"},"projectId":{"type":"integer"},"scriptId":{"type":"integer"},"scriptEpisodeId":{"type":"integer"}},"required":["snapshotId","projectId","scriptId","scriptEpisodeId"]}
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        Long snapshotId = JSONUtil.parseObj(toolInput).getLong("snapshotId");
        var params = JSONUtil.parseObj(toolInput);
        Long projectId = params.getLong("projectId");
        Long scriptId = params.getLong("scriptId");
        Long scriptEpisodeId = params.getLong("scriptEpisodeId");
        if (snapshotId == null || projectId == null || scriptId == null || scriptEpisodeId == null) {
            return error("缺少 snapshotId、projectId、scriptId 或 scriptEpisodeId");
        }
        AssetCatalogSnapshot snapshot = snapshotService.getById(snapshotId);
        if (!projectId.equals(snapshot.getProjectId()) || !scriptId.equals(snapshot.getScriptId())) {
            return error("资产目录快照不属于当前项目或剧本");
        }
        if (!scriptEpisodeId.equals(snapshot.getScriptEpisodeId())) {
            return error("资产目录快照不属于当前剧本分集");
        }
        if (!projectService.canAccessProject(projectId, context.getUserId())) {
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
