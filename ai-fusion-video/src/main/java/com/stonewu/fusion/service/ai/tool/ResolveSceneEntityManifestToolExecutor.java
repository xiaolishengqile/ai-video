package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.SceneEntityManifestService;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Resolves a parsed scene entity manifest before it is saved with a scene. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResolveSceneEntityManifestToolExecutor implements ToolExecutor {

    private final SceneEntityManifestService manifestService;
    private final ProjectService projectService;
    private final ScriptService scriptService;

    @Override
    public String getToolName() {
        return "resolve_scene_entity_manifest";
    }

    @Override
    public String getDisplayName() {
        return "解析场次实体清单";
    }

    @Override
    public String getToolDescription() {
        return "校验场次实体清单，优先匹配当前剧集已上传资产；缺失的核心/辅助实体会在当前集补建无图片占位资产，并返回带确定资产 ID 的清单。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectId": { "type": "integer", "description": "项目 ID" },
                    "scriptEpisodeId": { "type": "integer", "description": "当前剧本分集 ID" },
                    "entities": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "key": { "type": "string" },
                          "name": { "type": "string" },
                          "assetType": { "type": "string", "enum": ["character", "scene", "prop"] },
                          "entitySubtype": { "type": "string" },
                          "importance": { "type": "string", "enum": ["core", "supporting", "atmospheric"] },
                          "defaultForShots": { "type": "boolean" }
                        },
                        "required": ["key", "name", "assetType", "entitySubtype", "importance"]
                      }
                    }
                  },
                  "required": ["projectId", "scriptEpisodeId", "entities"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            var params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            Long scriptEpisodeId = params.getLong("scriptEpisodeId");
            JSONArray entities = params.getJSONArray("entities");
            if (projectId == null || scriptEpisodeId == null || entities == null) {
                return error("缺少 projectId、scriptEpisodeId 或 entities");
            }
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(projectId, context.getUserId())) {
                return error("无权访问该项目");
            }

            var episode = scriptService.getEpisodeById(scriptEpisodeId);
            if (!scriptService.getById(episode.getScriptId()).getProjectId().equals(projectId)) {
                return error("scriptEpisodeId 不属于指定项目");
            }
            SceneEntityManifest resolved = manifestService.resolve(projectId, context.getUserId(), episode.getEpisodeNumber(),
                    SceneEntityManifest.fromJson(JSONUtil.createObj().set("version", 1).set("entities", entities).toString()));
            int matchedCount = (int) resolved.entities().stream().filter(entity -> "matched".equals(entity.source())).count();
            int unmatchedCount = (int) resolved.entities().stream()
                    .filter(entity -> "unmatched_episode_catalog".equals(entity.source())).count();
            int autoCreatedCount = (int) resolved.entities().stream()
                    .filter(entity -> "auto_created_episode_catalog".equals(entity.source())).count();
            int filteredCount = (int) resolved.entities().stream()
                    .filter(entity -> "filtered_limit".equals(entity.source())).count();
            return JSONUtil.createObj()
                    .set("entityManifest", JSONUtil.parseObj(resolved.toJson()))
                    .set("matchedCount", matchedCount)
                    .set("unmatchedCount", unmatchedCount)
                    .set("autoCreatedCount", autoCreatedCount)
                    .set("filteredCount", filteredCount)
                    .toString();
        } catch (Exception e) {
            log.warn("解析场次实体清单失败", e);
            return error("解析失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
