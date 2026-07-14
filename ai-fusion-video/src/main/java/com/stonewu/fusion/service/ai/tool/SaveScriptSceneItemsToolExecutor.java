package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量保存场次工具（save_script_scene_items）
 * <p>
 * 支持覆盖或追加写入场次数据。需传入 episode_version 做乐观锁校验。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveScriptSceneItemsToolExecutor implements ToolExecutor {

    private static final Set<String> ASSET_TYPES = Set.of("character", "scene", "prop");
    private static final Set<String> IMPORTANCE = Set.of("core", "supporting", "atmospheric");

    private final ScriptService scriptService;
    private final AssetService assetService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "save_script_scene_items";
    }

    @Override
    public String getDisplayName() {
        return "保存场次数据";
    }

    @Override
    public String getToolDescription() {
        return """
                批量写入某集的场次和对白数据。调用前必须获取 episode_version（通过 save_script_episode 返回值或 get_script_episode 返回值获取）。
                工具内部按传入 scenes 数组顺序自动赋值 sort_order 和 scene_number，无需显式传入。
                                默认模式（overwriteMode=false 或不传）为追加模式，不删除已有场次；仅当 overwriteMode=true 时，才会先清空该集所有旧场次再写入当前数据。

                ## 分批调用规则（必须遵守！）
                - 每次调用 scenes 数组最多传入 3 个场次
                - 如果一集有超过 3 个场次，必须分多次调用：
                                    - 第一次调用：传入前 3 个场次，overwriteMode 必须设为 true（会清空旧数据）
                                    - 第二次及之后：传入后续 1-3 个场次，overwriteMode 不传或设为 false（追加模式）
                - 例如一集有 5 个场次，需要调用 2 次：
                                    - 第1次：scenes=[场次1, 场次2, 场次3]，overwriteMode=true
                                    - 第2次：scenes=[场次4, 场次5]，overwriteMode=false

                ## scene_heading 格式规则
                场景标头格式为：「内/外景 + 地点 + 时间」。示例：
                - "内景 张三家客厅 夜"
                - "外景 学校操场 日"
                - "内/外景 咖啡馆 黄昏"
                从标头中拆解出 location (地点)、time_of_day (时间)、int_ext (内/外景) 分别填入对应字段。

                ## dialogues 数组构建规则
                必须按剧本原文的严格时间顺序逐行拆解，每个元素对应一个 dialogue 条目。
                type 值：
                - 1：对白 — character_name 必填，content 填台词文本
                - 2：动作描写 — 原文中以 ▲ 开头的行，content 填动作描述
                - 3：画外音(V.O.) — character_name 填说话人，content 填画外音内容
                - 4：镜头指令 — 原文中【】包裹的内容，如【切】【闪回】
                - 5：环境描写 — 场景氛围、环境描述的文字

                **重要规则**：
                - 不要合并、跳过或省略任何行，严格按原文顺序逐行拆解
                - 对白（type=1）的 character_name 必须与 characters 列表中的角色名完全一致
                - parenthetical 填写括号注释，如"（低声）"、"（愤怒地）"
                - 若 character_asset_id 已知，请同时填入以建立角色关联
                - 优先传入 entity_manifest；已传 entity_manifest 时不要再额外传 character_asset_ids、scene_asset_id、prop_asset_ids
                - 未匹配到的资产不要用 null 占位，直接省略对应资产ID字段
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptEpisodeId": {
                            "type": "number",
                            "description": "集ID"
                        },
                        "episode_version": {
                            "type": "number",
                            "description": "集的版本号（从 get_script_episode 返回值获取，用于乐观锁校验）"
                        },
                        "scenes": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "scene_heading": { "type": "string", "description": "场景标头" },
                                    "location": { "type": "string", "description": "场景地点" },
                                    "time_of_day": { "type": "string", "description": "时间" },
                                    "int_ext": { "type": "string", "description": "内外景" },
                                    "characters": { "type": "array", "items": { "type": "string" }, "description": "出场角色名列表" },
                                    "character_asset_ids": { "type": "array", "items": { "type": ["number", "null"] }, "description": "角色资产ID列表" },
                                    "scene_asset_id": { "type": ["number", "null"], "description": "场景资产ID" },
                                    "prop_asset_ids": { "type": "array", "items": { "type": ["number", "null"] }, "description": "道具资产ID列表" },
                                    "entity_manifest": { "type": "object", "description": "resolve_scene_entity_manifest 返回的已解析场次实体清单；传入时会派生并校验资产关联字段" },
                                    "scene_description": { "type": "string", "description": "场景氛围概述" },
                                    "dialogues": {
                                        "type": "array",
                                        "items": {
                                            "type": "object",
                                            "properties": {
                                                "type": { "type": "number", "description": "1-对白 2-动作 3-VO 4-镜头指令 5-环境描写" },
                                                "character_name": { "type": "string" },
                                                "character_asset_id": { "type": ["number", "null"] },
                                                "parenthetical": { "type": "string" },
                                                "content": { "type": "string" }
                                            },
                                            "required": ["type", "content"]
                                        }
                                    }
                                },
                                "required": ["scene_heading"]
                            },
                            "description": "场次列表（按顺序排列，每次最多传入2个场次）"
                        },
                        "overwriteMode": {
                            "type": "boolean",
                            "description": "覆盖模式。true=先删除该集旧场次再写入当前 scenes；false或不传=追加到已有场次后。分批写入时通常仅第一次调用设为 true"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long scriptEpisodeId = params.getLong("scriptEpisodeId");
            Integer episodeVersion = params.getInt("episode_version");
            JSONArray scenesArray = params.getJSONArray("scenes");
            boolean overwriteMode = Boolean.TRUE.equals(params.getBool("overwriteMode"));

            if (scriptEpisodeId == null) {
                return missingRequired("scriptEpisodeId");
            }
            if (episodeVersion == null) {
                return missingRequired("episode_version");
            }
            if (scenesArray == null || scenesArray.isEmpty()) {
                return missingRequired("scenes");
            }
            ScriptEpisode episode = scriptService.getEpisodeById(scriptEpisodeId);
            Long projectId = scriptService.getById(episode.getScriptId()).getProjectId();
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(projectId, context.getUserId())) {
                return error("无权访问该项目");
            }

            List<ScriptSceneItem> sceneItems = new ArrayList<>();
            if (scenesArray != null) {
                for (int i = 0; i < scenesArray.size(); i++) {
                    JSONObject sceneJson = scenesArray.getJSONObject(i);
                    SceneEntityManifest manifest = parseManifest(sceneJson, projectId);
                    SceneAssetIds manifestAssetIds = manifest == null ? null : deriveAssetIds(manifest);
                    ScriptSceneItem item = ScriptSceneItem.builder()
                            .sceneHeading(sceneJson.getStr("scene_heading"))
                            .location(sceneJson.getStr("location"))
                            .timeOfDay(sceneJson.getStr("time_of_day"))
                            .intExt(sceneJson.getStr("int_ext"))
                            .sceneDescription(sceneJson.getStr("scene_description"))
                            .sceneAssetId(manifestAssetIds == null ? sceneJson.getLong("scene_asset_id") : manifestAssetIds.sceneAssetId())
                            .characters(sceneJson.containsKey("characters")
                                    ? sceneJson.getJSONArray("characters").toString()
                                    : null)
                            .characterAssetIds(manifestAssetIds == null
                                    ? cleanLongArrayJson(sceneJson.getJSONArray("character_asset_ids"))
                                    : JSONUtil.toJsonStr(manifestAssetIds.characterAssetIds()))
                            .propAssetIds(manifestAssetIds == null
                                    ? cleanLongArrayJson(sceneJson.getJSONArray("prop_asset_ids"))
                                    : JSONUtil.toJsonStr(manifestAssetIds.propAssetIds()))
                            .entityManifest(manifest == null ? null : manifest.toJson())
                            .dialogues(
                                    sceneJson.containsKey("dialogues") ? sceneJson.getJSONArray("dialogues").toString()
                                            : null)
                            .status(1)
                            .build();
                    sceneItems.add(item);
                }
            }

            scriptService.batchSaveSceneItems(scriptEpisodeId, episodeVersion, sceneItems, overwriteMode);

            JSONObject resultObj = JSONUtil.createObj()
                    .set("scriptEpisodeId", scriptEpisodeId)
                    .set("sceneCount", sceneItems.size())
                    .set("overwriteMode", overwriteMode)
                    .set("message", String.format("成功%s写入 %d 个场次", overwriteMode ? "覆盖" : "追加", sceneItems.size()));

            return resultObj.toString();
        } catch (Exception e) {
            log.error("保存场次数据失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "保存失败: " + e.getMessage()).toString();
        }
    }

    private SceneEntityManifest parseManifest(JSONObject sceneJson, Long projectId) {
        if (!sceneJson.containsKey("entity_manifest")) {
            return null;
        }
        SceneEntityManifest manifest = SceneEntityManifest.fromJson(sceneJson.getJSONObject("entity_manifest").toString());
        Set<String> keys = new LinkedHashSet<>();
        List<SceneEntity> normalized = new ArrayList<>();
        for (SceneEntity entity : manifest.entities()) {
            if (entity.key() == null || entity.key().isBlank() || !keys.add(entity.key())) {
                throw new IllegalArgumentException("entity_manifest 实体 key 必须非空且唯一");
            }
            if (!ASSET_TYPES.contains(entity.assetType())) {
                throw new IllegalArgumentException("entity_manifest 包含不支持的资产类型: " + entity.assetType());
            }
            if (!IMPORTANCE.contains(entity.importance())) {
                throw new IllegalArgumentException("entity_manifest 包含不支持的重要性: " + entity.importance());
            }
            if ("atmospheric".equals(entity.importance())
                    && (entity.defaultForShots() || entity.assetId() != null || entity.assetItemId() != null)) {
                throw new IllegalArgumentException("atmospheric 实体不能携带资产 ID 或默认继承标记");
            }
            if (!"atmospheric".equals(entity.importance())) {
                if ("ambiguous_episode_catalog".equals(entity.source())) {
                    throw new IllegalArgumentException("entity_manifest 候选资产存在歧义，必须先选择当前集候选资产");
                }
                if (entity.assetId() != null || entity.assetItemId() != null) {
                    if (entity.assetId() == null || entity.assetItemId() == null) {
                        throw new IllegalArgumentException("entity_manifest 资产关联必须同时提供主资产和子资产 ID");
                    }
                    AssetItem item = assetService.getItemById(entity.assetItemId());
                    Asset asset = assetService.getById(entity.assetId());
                    if (!entity.assetId().equals(item.getAssetId()) || !projectId.equals(asset.getProjectId())
                            || !entity.assetType().equals(asset.getType())) {
                        throw new IllegalArgumentException("entity_manifest 资产关联不属于当前项目或类型不匹配");
                    }
                }
            }
            boolean defaultForShots = "core".equals(entity.importance());
            normalized.add(new SceneEntity(entity.key(), entity.name(), entity.assetType(), entity.entitySubtype(),
                    entity.importance(), defaultForShots, entity.assetId(), entity.assetItemId(), entity.source()));
        }
        return new SceneEntityManifest(manifest.version(), normalized);
    }

    private String missingRequired(String field) {
        return JSONUtil.createObj()
                .set("status", "error")
                .set("message", "保存场次缺少必要参数: " + field
                        + "。请先调用 get_script_episode 获取 scriptEpisodeId 和 episode_version，再携带非空 scenes 数组重新调用 save_script_scene_items。")
                .toString();
    }

    private SceneAssetIds deriveAssetIds(SceneEntityManifest manifest) {
        Set<Long> characterAssetIds = new LinkedHashSet<>();
        Set<Long> propAssetIds = new LinkedHashSet<>();
        Long sceneAssetId = null;
        for (SceneEntity entity : manifest.entities()) {
            if ("atmospheric".equals(entity.importance())) {
                continue;
            }
            if (entity.assetId() == null || entity.assetItemId() == null) {
                continue;
            }
            switch (entity.assetType()) {
                case "character" -> characterAssetIds.add(entity.assetId());
                case "scene" -> {
                    if (sceneAssetId != null && !sceneAssetId.equals(entity.assetId())) {
                        throw new IllegalArgumentException("entity_manifest 包含多个场景资产");
                    }
                    sceneAssetId = entity.assetId();
                }
                case "prop" -> propAssetIds.add(entity.assetId());
                default -> throw new IllegalArgumentException("entity_manifest 包含不支持的资产类型: " + entity.assetType());
            }
        }
        return new SceneAssetIds(List.copyOf(characterAssetIds), sceneAssetId, List.copyOf(propAssetIds));
    }

    private String cleanLongArrayJson(JSONArray array) {
        if (array == null) {
            return null;
        }
        List<Long> values = new ArrayList<>();
        for (Object item : array) {
            if (item instanceof Number number) {
                values.add(number.longValue());
            }
        }
        return JSONUtil.toJsonStr(values);
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    private record SceneAssetIds(List<Long> characterAssetIds, Long sceneAssetId, List<Long> propAssetIds) {
    }
}
