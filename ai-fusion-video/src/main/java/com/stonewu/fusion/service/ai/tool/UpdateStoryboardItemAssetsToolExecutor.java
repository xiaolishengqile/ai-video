package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 更新分镜镜头资产绑定。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateStoryboardItemAssetsToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final AssetService assetService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "update_storyboard_item_assets";
    }

    @Override
    public String getDisplayName() {
        return "更新镜头资产";
    }

    @Override
    public String getToolDescription() {
        return """
                更新单个分镜镜头的角色、场景和道具子资产绑定。
                只用于资产匹配或人工确认后的写回，不会修改镜头内容、提示词、图片或视频。
                所有 ID 必须是 AssetItem.id，且必须属于该镜头对应剧集的资产。
                未传入的资产字段会保留原值；只有显式传入的字段会被覆盖。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "storyboardItemId": { "type": "integer", "description": "分镜条目ID" },
                    "characterIds": { "type": "array", "items": { "type": "integer" }, "description": "角色子资产ID数组" },
                    "sceneAssetItemId": { "type": "integer", "description": "主场景子资产ID" },
                    "sceneAssetItemIds": { "type": "array", "items": { "type": "integer" }, "description": "场景子资产ID数组，首项为主场景" },
                    "propIds": { "type": "array", "items": { "type": "integer" }, "description": "道具子资产ID数组" }
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
                return error("缺少 storyboardItemId");
            }

            StoryboardItem item = storyboardService.getItemById(itemId);
            Storyboard storyboard = storyboardService.getById(item.getStoryboardId());
            if (context == null || context.getUserId() == null
                    || !projectService.canAccessProject(storyboard.getProjectId(), context.getUserId())) {
                return error("无权访问该项目");
            }

            StoryboardEpisode episode = storyboardService.getEpisodeById(item.getStoryboardEpisodeId());
            Integer episodeNumber = episode.getEpisodeNumber();
            if (episodeNumber == null) {
                return error("分镜集缺少集号，无法限定当前集资产");
            }

            boolean hasCharacterIds = params.containsKey("characterIds");
            boolean hasPropIds = params.containsKey("propIds");
            boolean hasSceneAssetItemId = params.containsKey("sceneAssetItemId");
            boolean hasSceneAssetItemIds = params.containsKey("sceneAssetItemIds");

            List<Long> characterIds = hasCharacterIds
                    ? validateItems(params.getJSONArray("characterIds"), "character", storyboard.getProjectId(), episodeNumber)
                    : parseIds(item.getCharacterIds());
            List<Long> propIds = hasPropIds
                    ? validateItems(params.getJSONArray("propIds"), "prop", storyboard.getProjectId(), episodeNumber)
                    : parseIds(item.getPropIds());

            Long primarySceneId = item.getSceneAssetItemId();
            List<Long> normalizedSceneIds = parseIds(item.getSceneAssetItemIds());
            if (hasSceneAssetItemId || hasSceneAssetItemIds) {
                List<Long> sceneIds = validateItems(params.getJSONArray("sceneAssetItemIds"),
                        "scene", storyboard.getProjectId(), episodeNumber);
                Long sceneAssetItemId = params.getLong("sceneAssetItemId");
                if (sceneAssetItemId != null) {
                    validateItem(sceneAssetItemId, "scene", storyboard.getProjectId(), episodeNumber);
                }

                LinkedHashSet<Long> mergedScenes = new LinkedHashSet<>();
                if (sceneAssetItemId != null) {
                    mergedScenes.add(sceneAssetItemId);
                }
                mergedScenes.addAll(sceneIds);
                normalizedSceneIds = List.copyOf(mergedScenes);
                primarySceneId = sceneAssetItemId != null
                        ? sceneAssetItemId
                        : normalizedSceneIds.isEmpty() ? null : normalizedSceneIds.getFirst();
            }

            StoryboardItem updated = storyboardService.updateItemAssets(
                    itemId,
                    JSONUtil.toJsonStr(characterIds),
                    primarySceneId,
                    JSONUtil.toJsonStr(normalizedSceneIds),
                    JSONUtil.toJsonStr(propIds));

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardItemId", updated.getId())
                    .set("characterCount", characterIds.size())
                    .set("hasScene", primarySceneId != null)
                    .set("propCount", propIds.size())
                    .set("message", "镜头资产已更新")
                    .toString();
        } catch (Exception e) {
            log.error("[update_storyboard_item_assets] 更新失败", e);
            return error("更新失败: " + e.getMessage());
        }
    }

    private List<Long> parseIds(String idsJson) {
        if (idsJson == null || idsJson.isBlank()) {
            return List.of();
        }
        try {
            JSONArray ids = JSONUtil.parseArray(idsJson);
            LinkedHashSet<Long> result = new LinkedHashSet<>();
            for (Object value : ids) {
                if (value == null) continue;
                result.add(value instanceof Number number ? number.longValue() : Long.valueOf(value.toString()));
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<Long> validateItems(JSONArray ids, String expectedType, Long projectId, Integer episodeNumber) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (ids == null) {
            return List.of();
        }
        for (Object value : ids) {
            if (value == null) continue;
            Long id = value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
            validateItem(id, expectedType, projectId, episodeNumber);
            result.add(id);
        }
        return List.copyOf(result);
    }

    private void validateItem(Long itemId, String expectedType, Long projectId, Integer episodeNumber) {
        AssetItem item = assetService.getItemById(itemId);
        Asset asset = assetService.getById(item.getAssetId());
        if (!projectId.equals(asset.getProjectId())) {
            throw new IllegalArgumentException("资产不属于当前项目: " + itemId);
        }
        if (!episodeNumber.equals(asset.getEpisodeNumber())) {
            throw new IllegalArgumentException("资产不属于当前集: " + itemId);
        }
        if (!expectedType.equals(asset.getType())) {
            throw new IllegalArgumentException("资产类型不匹配: " + itemId);
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
