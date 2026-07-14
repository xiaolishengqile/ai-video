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

import java.util.HashMap;
import java.util.Map;

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
        return "校验场次实体清单，只在当前剧集匹配资产。可传入 search_episode_asset_candidates 返回的 selectedAssetId；候选歧义时不会自动新建资产。";
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
                          "defaultForShots": { "type": "boolean" },
                          "selectedAssetId": { "type": "integer", "description": "调用 search_episode_asset_candidates 后由 AI 选择的当前集资产 ID；只作为本次解析输入，不写入清单" }
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
            Map<String, Long> selectedAssetIds = new HashMap<>();
            JSONArray manifestEntities = new JSONArray();
            for (Object value : entities) {
                var entity = JSONUtil.parseObj(value);
                String key = entity.getStr("key");
                Long selectedAssetId = entity.getLong("selectedAssetId");
                if (key != null && selectedAssetId != null) {
                    selectedAssetIds.put(key, selectedAssetId);
                }
                entity.remove("selectedAssetId");
                manifestEntities.add(entity);
            }
            SceneEntityManifest resolved = manifestService.resolve(projectId, context.getUserId(), episode.getEpisodeNumber(),
                    SceneEntityManifest.fromJson(JSONUtil.createObj().set("version", 1).set("entities", manifestEntities).toString()),
                    selectedAssetIds);
            int matchedCount = (int) resolved.entities().stream().filter(entity -> entity.source().startsWith("matched")).count();
            int unmatchedCount = (int) resolved.entities().stream()
                    .filter(entity -> "unmatched_episode_catalog".equals(entity.source())).count();
            int ambiguousCount = (int) resolved.entities().stream()
                    .filter(entity -> "ambiguous_episode_catalog".equals(entity.source())).count();
            int autoCreatedCount = (int) resolved.entities().stream()
                    .filter(entity -> "auto_created_episode_catalog".equals(entity.source())).count();
            int filteredCount = (int) resolved.entities().stream()
                    .filter(entity -> "filtered_limit".equals(entity.source())).count();
            int selectedCount = (int) resolved.entities().stream()
                    .filter(entity -> "matched_selected".equals(entity.source())).count();
            JSONArray assetResolutionFeedback = new JSONArray();
            resolved.entities().forEach(entity -> {
                if ("ambiguous_episode_catalog".equals(entity.source())) {
                    assetResolutionFeedback.add(JSONUtil.createObj()
                            .set("entityKey", entity.key()).set("entityName", entity.name())
                            .set("status", "ambiguous")
                            .set("message", "当前集存在多个同等候选资产，请选择后再保存场次"));
                } else if ("auto_created_episode_catalog".equals(entity.source())) {
                    assetResolutionFeedback.add(JSONUtil.createObj()
                            .set("entityKey", entity.key()).set("entityName", entity.name())
                            .set("status", "unmatched_created")
                            .set("message", "当前集未匹配到资产，已创建待补图资产；补图前不会提供图片参考"));
                } else if ("unmatched_episode_catalog".equals(entity.source())) {
                    assetResolutionFeedback.add(JSONUtil.createObj()
                            .set("entityKey", entity.key()).set("entityName", entity.name())
                            .set("status", "unmatched")
                            .set("message", "当前集资产缺少可用初始子项"));
                }
            });
            return JSONUtil.createObj()
                    .set("entityManifest", JSONUtil.parseObj(resolved.toJson()))
                    .set("matchedCount", matchedCount)
                    .set("unmatchedCount", unmatchedCount)
                    .set("ambiguousCount", ambiguousCount)
                    .set("autoCreatedCount", autoCreatedCount)
                    .set("filteredCount", filteredCount)
                    .set("selectedCount", selectedCount)
                    .set("assetResolutionFeedback", assetResolutionFeedback)
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
