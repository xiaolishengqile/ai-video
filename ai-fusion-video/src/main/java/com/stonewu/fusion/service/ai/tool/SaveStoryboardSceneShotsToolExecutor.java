package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.asset.AssetCatalogSnapshotService;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.SceneEntityManifestService;
import com.stonewu.fusion.service.script.ScriptAssetPrebindingService;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saves one storyboard scene and deterministically inherits its core script-scene assets. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveStoryboardSceneShotsToolExecutor implements ToolExecutor {

    private static final Set<String> EXCLUSION_REASONS = Set.of("offscreen", "close_up", "not_yet_appeared");

    private final StoryboardService storyboardService;
    private final ScriptSceneItemMapper scriptSceneItemMapper;
    private final SceneEntityManifestService sceneEntityManifestService;
    private final ScriptService scriptService;
    private final AssetService assetService;
    private final AssetCatalogSnapshotService snapshotService;
    private final ProjectService projectService;
    private final ScriptAssetPrebindingService prebindingService;

    @Override
    public String getToolName() {
        return "save_storyboard_scene_shots";
    }

    @Override
    public String getDisplayName() {
        return "保存场次分镜";
    }

    @Override
    public String getToolDescription() {
        return """
                创建一个分镜场次，并批量保存该场次下的所有镜头。
                scriptSceneItemId 必须属于 storyboardEpisodeId 已绑定的剧本分集。镜头默认继承该场核心实体；
                只有因 offscreen、close_up 或 not_yet_appeared 不入镜时，才能通过 excludedDefaultEntityKeys 排除。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardId": { "type": "number", "description": "所属分镜脚本ID（必填）" },
                        "assetCatalogSnapshotId": { "type": "number", "description": "当前剧本集固定资产目录快照ID（必填）" },
                        "storyboardEpisodeId": { "type": "number", "description": "所属分镜集ID（必填）" },
                        "scriptSceneItemId": { "type": "number", "description": "关联的剧本场次ID（必填）" },
                        "sceneNumber": { "type": "string", "description": "场次编号（必填）" },
                        "sceneHeading": { "type": "string" },
                        "location": { "type": "string" },
                        "timeOfDay": { "type": "string" },
                        "intExt": { "type": "string" },
                        "sortOrder": { "type": "number", "description": "场次排序序号（从0开始，默认0）" },
                        "shots": {
                            "type": "array",
                            "description": "镜头列表（必填）",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "content": { "type": "string", "description": "画面内容描述（必填）" },
                                    "shotType": { "type": "string", "description": "景别" },
                                    "duration": { "type": "number", "description": "预估时长（秒）" },
                                    "dialogue": { "type": "string", "description": "台词/旁白" },
                                    "sound": { "type": "string" },
                                    "soundEffect": { "type": "string" },
                                    "music": { "type": "string" },
                                    "cameraMovement": { "type": "string" },
                                    "cameraAngle": { "type": "string" },
                                    "cameraEquipment": { "type": "string" },
                                    "focalLength": { "type": "string" },
                                    "transition": { "type": "string" },
                                    "sceneExpectation": { "type": "string" },
                                    "characterIds": { "type": "array", "items": { "type": "number" }, "description": "显式角色子资产ID" },
                                    "sceneAssetItemId": { "type": "number", "description": "显式主场景子资产ID；兼容旧字段" },
                                    "sceneAssetItemIds": { "type": "array", "items": { "type": "number" }, "description": "显式附加场景子资产ID；核心默认场景始终保留为首个主场景" },
                                    "propIds": { "type": "array", "items": { "type": "number" }, "description": "显式道具子资产ID" },
                                    "excludedDefaultEntityKeys": {
                                        "type": "array",
                                        "description": "要排除的核心默认实体；每项必须含 key 和 reason",
                                        "items": {
                                            "type": "object",
                                            "properties": {
                                                "key": { "type": "string", "description": "entityManifest 中的精确实体 key" },
                                                "reason": { "type": "string", "enum": ["offscreen", "close_up", "not_yet_appeared"] }
                                            },
                                            "required": ["key", "reason"]
                                        }
                                    },
                                    "remark": { "type": "string" }
                                },
                                "required": ["content"]
                            }
                        }
                    },
                    "required": ["storyboardId", "assetCatalogSnapshotId", "storyboardEpisodeId", "scriptSceneItemId", "sceneNumber", "shots"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardId = params.getLong("storyboardId");
            Long assetCatalogSnapshotId = params.getLong("assetCatalogSnapshotId");
            Long storyboardEpisodeId = params.getLong("storyboardEpisodeId");
            Long scriptSceneItemId = params.getLong("scriptSceneItemId");
            String sceneNumber = params.getStr("sceneNumber");
            JSONArray shotsArr = params.getJSONArray("shots");
            validateRequired(storyboardId, assetCatalogSnapshotId, storyboardEpisodeId, scriptSceneItemId, sceneNumber, shotsArr);

            SceneContext sceneContext = loadSceneContext(storyboardId, assetCatalogSnapshotId, storyboardEpisodeId, scriptSceneItemId, context);
            List<ShotAssets> shotAssets = new ArrayList<>();
            for (int i = 0; i < shotsArr.size(); i++) {
                shotAssets.add(resolveShotAssets(shotsArr.getJSONObject(i), sceneContext));
            }

            StoryboardScene savedScene = storyboardService.createScene(StoryboardScene.builder()
                    .storyboardId(storyboardId)
                    .episodeId(storyboardEpisodeId)
                    .sceneNumber(sceneNumber)
                    .sceneHeading(params.getStr("sceneHeading"))
                    .location(params.getStr("location"))
                    .timeOfDay(params.getStr("timeOfDay"))
                    .intExt(params.getStr("intExt"))
                    .sortOrder(params.getInt("sortOrder", 0))
                    .status(1)
                    .build());
            log.info("[save_storyboard_scene_shots] 场次创建成功: sceneId={}, sceneNumber={}",
                    savedScene.getId(), sceneNumber);

            List<StoryboardItem> items = new ArrayList<>();
            List<JSONObject> bindingSources = new ArrayList<>();
            for (int i = 0; i < shotsArr.size(); i++) {
                JSONObject shot = shotsArr.getJSONObject(i);
                ShotAssets assets = shotAssets.get(i);
                items.add(StoryboardItem.builder()
                        .storyboardId(storyboardId)
                        .storyboardEpisodeId(storyboardEpisodeId)
                        .storyboardSceneId(savedScene.getId())
                        .sortOrder(i)
                        .shotNumber(String.valueOf(i + 1))
                        .shotType(shot.getStr("shotType"))
                        .content(shot.getStr("content"))
                        .sceneExpectation(shot.getStr("sceneExpectation"))
                        .dialogue(shot.getStr("dialogue"))
                        .sound(shot.getStr("sound"))
                        .soundEffect(shot.getStr("soundEffect"))
                        .music(shot.getStr("music"))
                        .cameraMovement(shot.getStr("cameraMovement"))
                        .cameraAngle(shot.getStr("cameraAngle"))
                        .cameraEquipment(shot.getStr("cameraEquipment"))
                        .focalLength(shot.getStr("focalLength"))
                        .transition(shot.getStr("transition"))
                        .characterIds(JSONUtil.toJsonStr(assets.characterIds()))
                        .sceneAssetItemId(assets.sceneAssetItemId())
                        .sceneAssetItemIds(JSONUtil.toJsonStr(assets.sceneAssetItemIds()))
                        .propIds(JSONUtil.toJsonStr(assets.propIds()))
                        .customData(JSONUtil.createObj()
                                .set("assetBindingSource", assets.bindingSource(i + 1)).toString())
                        .remark(shot.getStr("remark"))
                        .duration(shot.get("duration") != null ? new BigDecimal(shot.getStr("duration")) : null)
                        .aiGenerated(true)
                        .status(1)
                        .build());
                bindingSources.add(assets.bindingSource(i + 1));
            }

            storyboardService.batchCreateItems(items);
            log.info("[save_storyboard_scene_shots] 镜头批量创建成功: sceneId={}, count={}",
                    savedScene.getId(), items.size());
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardSceneId", savedScene.getId())
                    .set("sceneNumber", sceneNumber)
                    .set("shotCount", items.size())
                    .set("assetBindingSources", bindingSources)
                    .set("message", "场次分镜保存成功，共 " + items.size() + " 个镜头").toString();
        } catch (MissingCoreAssetException e) {
            return JSONUtil.createObj()
                    .set("status", "blocked_missing_assets")
                    .set("message", "核心场次实体缺少资产子项，已记录为待补资产；请补充或关联资产后重跑该场次")
                    .set("missingAssets", e.missingAssets())
                    .toString();
        } catch (Exception e) {
            log.error("保存场次分镜失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "操作失败: " + e.getMessage()).toString();
        }
    }

    private void validateRequired(Long storyboardId, Long assetCatalogSnapshotId, Long storyboardEpisodeId, Long scriptSceneItemId,
                                  String sceneNumber, JSONArray shots) {
        if (storyboardId == null) throw new IllegalArgumentException("缺少 storyboardId");
        if (assetCatalogSnapshotId == null) throw new IllegalArgumentException("缺少 assetCatalogSnapshotId");
        if (storyboardEpisodeId == null) throw new IllegalArgumentException("缺少 storyboardEpisodeId");
        if (scriptSceneItemId == null) throw new IllegalArgumentException("缺少 scriptSceneItemId");
        if (sceneNumber == null || sceneNumber.isBlank()) throw new IllegalArgumentException("缺少 sceneNumber");
        if (shots == null || shots.isEmpty()) throw new IllegalArgumentException("缺少 shots 或为空");
    }

    private SceneContext loadSceneContext(Long storyboardId, Long assetCatalogSnapshotId, Long storyboardEpisodeId, Long scriptSceneItemId,
                                          ToolExecutionContext context) {
        Storyboard storyboard = storyboardService.getById(storyboardId);
        if (context == null || context.getUserId() == null
                || !projectService.canAccessProject(storyboard.getProjectId(), context.getUserId())) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        StoryboardEpisode episode = storyboardService.getEpisodeById(storyboardEpisodeId);
        if (!storyboardId.equals(episode.getStoryboardId())) {
            throw new IllegalArgumentException("storyboardEpisodeId 不属于指定 storyboardId");
        }
        if (episode.getScriptEpisodeId() == null) {
            throw new IllegalArgumentException("分镜集未关联剧本分集");
        }
        ScriptSceneItem scriptScene = scriptSceneItemMapper.selectById(scriptSceneItemId);
        if (scriptScene == null) {
            throw new IllegalArgumentException("剧本场次不存在: " + scriptSceneItemId);
        }
        if (!episode.getScriptEpisodeId().equals(scriptScene.getEpisodeId())) {
            throw new IllegalArgumentException("剧本场次不属于分镜集关联的剧本分集");
        }
        var scriptEpisode = scriptService.getEpisodeById(episode.getScriptEpisodeId());
        Integer episodeNumber = scriptEpisode.getEpisodeNumber();
        if (episodeNumber == null) {
            throw new IllegalArgumentException("剧本分集缺少集号");
        }
        SceneEntityManifest manifest = SceneEntityManifest.fromJson(scriptScene.getEntityManifest());
        List<SceneEntity> coreDefaults = manifest.entities().stream()
                .filter(entity -> "core".equals(entity.importance()))
                .filter(SceneEntity::defaultForShots)
                .toList();
        List<SceneEntity> missingCoreAssets = coreDefaults.stream()
                .filter(entity -> entity.assetItemId() == null)
                .toList();
        if (!missingCoreAssets.isEmpty()) {
            prebindingService.recordMissingAssetRequirements(storyboard.getProjectId(), scriptEpisode.getScriptId(),
                    episode.getScriptEpisodeId(), episodeNumber, scriptSceneItemId, scriptScene.getSceneNumber(),
                    missingCoreAssets);
            throw new MissingCoreAssetException(missingAssets(missingCoreAssets, episodeNumber,
                    scriptSceneItemId, scriptScene.getSceneNumber()));
        }
        AssetCatalogSnapshot snapshot = snapshotService.getById(assetCatalogSnapshotId);
        if (!storyboard.getProjectId().equals(snapshot.getProjectId())
                || !scriptEpisode.getScriptId().equals(snapshot.getScriptId())
                || !episode.getScriptEpisodeId().equals(snapshot.getScriptEpisodeId())) {
            throw new IllegalArgumentException("资产目录快照不属于当前项目、剧本或剧本分集");
        }
        Set<Long> snapshotItemIds = snapshotService.itemIds(snapshot);
        return new SceneContext(coreDefaults, storyboard.getProjectId(), episodeNumber, snapshotItemIds);
    }

    private ShotAssets resolveShotAssets(JSONObject shot, SceneContext context) {
        validateItems(assetIds(context.coreDefaults(), "character"), "character", "角色资产子项类型不匹配", context);
        validateItems(assetIds(context.coreDefaults(), "scene"), "scene", "场景资产子项类型不匹配", context);
        validateItems(assetIds(context.coreDefaults(), "prop"), "prop", "道具资产子项类型不匹配", context);
        Map<String, String> exclusions = parseExclusions(shot.getJSONArray("excludedDefaultEntityKeys"), context.coreDefaults());
        List<SceneEntity> retainedDefaults = context.coreDefaults().stream()
                .filter(entity -> !exclusions.containsKey(entity.key()))
                .toList();
        if (hasCoreScene(context.coreDefaults()) && !hasCoreScene(retainedDefaults)) {
            throw new IllegalArgumentException("不能排除该场唯一核心场景");
        }

        List<Long> explicitCharacters = ids(shot.getJSONArray("characterIds"));
        Long explicitScene = shot.getLong("sceneAssetItemId");
        List<Long> explicitScenes = ids(shot.getJSONArray("sceneAssetItemIds"));
        List<Long> explicitProps = ids(shot.getJSONArray("propIds"));
        validateItems(explicitCharacters, "character", "角色资产子项类型不匹配", context);
        if (explicitScene != null) validateItems(List.of(explicitScene), "scene", "场景资产子项类型不匹配", context);
        validateItems(explicitScenes, "scene", "场景资产子项类型不匹配", context);
        validateItems(explicitProps, "prop", "道具资产子项类型不匹配", context);

        List<Long> defaultCharacters = assetIds(retainedDefaults, "character");
        List<Long> defaultScenes = assetIds(retainedDefaults, "scene");
        List<Long> defaultProps = assetIds(retainedDefaults, "prop");
        validateItems(defaultCharacters, "character", "角色资产子项类型不匹配", context);
        validateItems(defaultScenes, "scene", "场景资产子项类型不匹配", context);
        validateItems(defaultProps, "prop", "道具资产子项类型不匹配", context);

        if (explicitScene != null && !defaultScenes.isEmpty() && !defaultScenes.contains(explicitScene)) {
            throw new IllegalArgumentException("不能替换核心默认场景");
        }
        List<Long> effectiveDefaultScenes = explicitScene == null ? defaultScenes : List.of();
        Long scene = explicitScene != null ? explicitScene : onlyScene(effectiveDefaultScenes);
        if (scene == null && !explicitScenes.isEmpty()) {
            scene = explicitScenes.getFirst();
        }
        List<Long> scenes = scene == null ? explicitScenes : merge(List.of(scene), explicitScenes);
        List<Long> characters = merge(defaultCharacters, explicitCharacters);
        List<Long> props = merge(defaultProps, explicitProps);
        List<Long> explicit = merge(explicitCharacters,
                explicitScene == null ? explicitScenes : merge(List.of(explicitScene), explicitScenes), explicitProps);
        List<Long> excluded = exclusions.keySet().stream()
                .map(key -> findEntity(context.coreDefaults(), key).assetItemId())
                .toList();
        if (explicit.stream().anyMatch(excluded::contains)) {
            throw new IllegalArgumentException("同一资产不能同时排除并显式加入");
        }
        List<Long> inherited = merge(without(defaultCharacters, explicit), without(effectiveDefaultScenes, explicit),
                without(defaultProps, explicit));
        return new ShotAssets(characters, scene, scenes, props, inherited, explicit, excluded);
    }

    private Map<String, String> parseExclusions(JSONArray input, List<SceneEntity> coreDefaults) {
        Map<String, String> exclusions = new LinkedHashMap<>();
        if (input == null) return exclusions;
        for (Object value : input) {
            JSONObject exclusion = JSONUtil.parseObj(value);
            String key = exclusion.getStr("key");
            String reason = exclusion.getStr("reason");
            if (key == null || key.isBlank() || !EXCLUSION_REASONS.contains(reason)) {
                throw new IllegalArgumentException("excludedDefaultEntityKeys 必须提供实体 key 和合法 reason");
            }
            if (findEntity(coreDefaults, key) == null) {
                throw new IllegalArgumentException("不能排除非核心默认实体: " + key);
            }
            if (exclusions.putIfAbsent(key, reason) != null) {
                throw new IllegalArgumentException("excludedDefaultEntityKeys 包含重复实体: " + key);
            }
        }
        return exclusions;
    }

    private List<Long> ids(JSONArray array) {
        if (array == null) return List.of();
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (Object value : array) {
            Long id = value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
            if (id == null) throw new IllegalArgumentException("资产子项 ID 不能为空");
            values.add(id);
        }
        return List.copyOf(values);
    }

    private void validateItems(List<Long> itemIds, String expectedType, String mismatchMessage, SceneContext context) {
        for (Long itemId : itemIds) {
            if (!context.snapshotItemIds().contains(itemId)) {
                throw new IllegalArgumentException("资产子项不在固定资产目录快照中: " + itemId);
            }
            AssetItem item = assetService.getItemById(itemId);
            if (item.getAssetId() == null) throw new IllegalArgumentException("资产子项缺少主资产: " + itemId);
            Asset asset = assetService.getById(item.getAssetId());
            if (!context.projectId().equals(asset.getProjectId())) throw new IllegalArgumentException("资产不属于当前项目: " + itemId);
            if (!context.episodeNumber().equals(asset.getEpisodeNumber())) throw new IllegalArgumentException("资产不属于当前剧集: " + itemId);
            if (!expectedType.equals(asset.getType())) throw new IllegalArgumentException(mismatchMessage + ": " + itemId);
        }
    }

    private boolean hasCoreScene(List<SceneEntity> entities) {
        return entities.stream().anyMatch(entity -> "scene".equals(entity.assetType()));
    }

    private SceneEntity findEntity(List<SceneEntity> entities, String key) {
        return entities.stream().filter(entity -> key.equals(entity.key())).findFirst().orElse(null);
    }

    private List<Long> assetIds(List<SceneEntity> entities, String type) {
        return entities.stream().filter(entity -> type.equals(entity.assetType()))
                .map(SceneEntity::assetItemId).toList();
    }

    private Long onlyScene(List<Long> scenes) {
        if (scenes.size() > 1) throw new IllegalArgumentException("场次存在多个核心场景，无法确定默认场景");
        return scenes.isEmpty() ? null : scenes.getFirst();
    }

    @SafeVarargs
    private List<Long> merge(List<Long>... values) {
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        for (List<Long> value : values) merged.addAll(value);
        return List.copyOf(merged);
    }

    private List<Long> without(List<Long> values, List<Long> excluded) {
        return values.stream().filter(value -> !excluded.contains(value)).toList();
    }

    private JSONArray missingAssets(List<SceneEntity> entities, Integer episodeNumber,
                                    Long scriptSceneItemId, String sceneNumber) {
        JSONArray result = new JSONArray();
        for (SceneEntity entity : entities) {
            result.add(JSONUtil.createObj()
                    .set("entityKey", entity.key())
                    .set("entityName", entity.name())
                    .set("assetType", entity.assetType())
                    .set("entitySubtype", entity.entitySubtype())
                    .set("episodeNumber", episodeNumber)
                    .set("scriptSceneItemId", scriptSceneItemId)
                    .set("sceneNumber", sceneNumber)
                    .set("suggestedLocation", suggestedLocation(episodeNumber, entity.assetType()))
                    .set("suggestedAssetName", suggestedAssetName(entity)));
        }
        return result;
    }

    private String suggestedLocation(Integer episodeNumber, String assetType) {
        return "项目资产 > 第" + episodeNumber + "集 > " + switch (assetType) {
            case "character" -> "角色";
            case "scene" -> "场景";
            case "prop" -> "道具";
            default -> "资产";
        };
    }

    private String suggestedAssetName(SceneEntity entity) {
        String suffix = switch (entity.assetType()) {
            case "character" -> "mecha".equals(entity.entitySubtype()) ? "机甲本体完整档案" : "完整档案";
            case "scene" -> "场景设定图";
            case "prop" -> "道具设定图";
            default -> "完整档案";
        };
        return entity.name() + "｜" + suffix;
    }

    private static class MissingCoreAssetException extends RuntimeException {
        private final JSONArray missingAssets;

        MissingCoreAssetException(JSONArray missingAssets) {
            this.missingAssets = missingAssets;
        }

        JSONArray missingAssets() {
            return missingAssets;
        }
    }

    private record SceneContext(List<SceneEntity> coreDefaults, Long projectId, Integer episodeNumber,
                                Set<Long> snapshotItemIds) {
    }

    private record ShotAssets(List<Long> characterIds, Long sceneAssetItemId, List<Long> sceneAssetItemIds, List<Long> propIds,
                              List<Long> inherited, List<Long> explicit, List<Long> excluded) {
        private JSONObject bindingSource(int shotNumber) {
            return JSONUtil.createObj().set("shotNumber", shotNumber)
                    .set("inherited", inherited).set("explicit", explicit).set("excluded", excluded);
        }
    }
}
