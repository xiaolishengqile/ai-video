package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 保存场次分镜工具（save_storyboard_scene_shots）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveStoryboardSceneShotsToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final ScriptSceneItemMapper scriptSceneItemMapper;
    private final ProjectService projectService;

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
                分镜生成阶段可以不传资产字段；镜头资产可由后续 AI匹配资产 或人工选择补充。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardId": { "type": "number", "description": "所属分镜脚本ID（必填）" },
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
                                    "characterIds": { "type": "array", "items": { "type": "number" }, "description": "可选角色子资产ID" },
                                    "sceneAssetItemId": { "type": "number", "description": "可选主场景子资产ID" },
                                    "sceneAssetItemIds": { "type": "array", "items": { "type": "number" }, "description": "可选场景子资产ID列表" },
                                    "propIds": { "type": "array", "items": { "type": "number" }, "description": "可选道具子资产ID" },
                                    "remark": { "type": "string" }
                                },
                                "required": ["content"]
                            }
                        }
                    },
                    "required": ["storyboardId", "storyboardEpisodeId", "scriptSceneItemId", "sceneNumber", "shots"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardId = params.getLong("storyboardId");
            Long storyboardEpisodeId = params.getLong("storyboardEpisodeId");
            Long scriptSceneItemId = params.getLong("scriptSceneItemId");
            String sceneNumber = params.getStr("sceneNumber");
            JSONArray shotsArr = params.getJSONArray("shots");
            validateRequired(storyboardId, storyboardEpisodeId, scriptSceneItemId, sceneNumber, shotsArr);
            ScriptSceneItem scriptScene = validateAccess(storyboardId, storyboardEpisodeId, scriptSceneItemId, context);

            StoryboardScene savedScene = storyboardService.createScene(StoryboardScene.builder()
                    .storyboardId(storyboardId)
                    .episodeId(storyboardEpisodeId)
                    .sceneNumber(sceneNumber)
                    .sceneHeading(resolveSceneHeading(params.getStr("sceneHeading"), scriptScene))
                    .location(params.getStr("location"))
                    .timeOfDay(params.getStr("timeOfDay"))
                    .intExt(params.getStr("intExt"))
                    .sortOrder(params.getInt("sortOrder", 0))
                    .status(1)
                    .build());

            List<StoryboardItem> items = new ArrayList<>();
            for (int i = 0; i < shotsArr.size(); i++) {
                JSONObject shot = shotsArr.getJSONObject(i);
                List<Long> sceneIds = ids(shot.getJSONArray("sceneAssetItemIds"));
                Long sceneId = shot.getLong("sceneAssetItemId");
                if (sceneId == null && !sceneIds.isEmpty()) {
                    sceneId = sceneIds.getFirst();
                }
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
                        .characterIds(JSONUtil.toJsonStr(ids(shot.getJSONArray("characterIds"))))
                        .sceneAssetItemId(sceneId)
                        .sceneAssetItemIds(JSONUtil.toJsonStr(sceneIds))
                        .propIds(JSONUtil.toJsonStr(ids(shot.getJSONArray("propIds"))))
                        .remark(shot.getStr("remark"))
                        .duration(shot.get("duration") != null ? new BigDecimal(shot.getStr("duration")) : null)
                        .aiGenerated(true)
                        .status(1)
                        .build());
            }

            storyboardService.batchCreateItems(items);
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardSceneId", savedScene.getId())
                    .set("sceneNumber", sceneNumber)
                    .set("shotCount", items.size())
                    .set("message", "场次分镜保存成功，共 " + items.size() + " 个镜头")
                    .toString();
        } catch (Exception e) {
            log.error("保存场次分镜失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "操作失败: " + e.getMessage()).toString();
        }
    }

    private void validateRequired(Long storyboardId, Long storyboardEpisodeId, Long scriptSceneItemId,
                                  String sceneNumber, JSONArray shots) {
        if (storyboardId == null) throw new IllegalArgumentException("缺少 storyboardId");
        if (storyboardEpisodeId == null) throw new IllegalArgumentException("缺少 storyboardEpisodeId");
        if (scriptSceneItemId == null) throw new IllegalArgumentException("缺少 scriptSceneItemId");
        if (sceneNumber == null || sceneNumber.isBlank()) throw new IllegalArgumentException("缺少 sceneNumber");
        if (shots == null || shots.isEmpty()) throw new IllegalArgumentException("缺少 shots 或为空");
    }

    private ScriptSceneItem validateAccess(Long storyboardId, Long storyboardEpisodeId, Long scriptSceneItemId,
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
        ScriptSceneItem scriptScene = scriptSceneItemMapper.selectById(scriptSceneItemId);
        if (scriptScene == null) {
            throw new IllegalArgumentException("剧本场次不存在: " + scriptSceneItemId);
        }
        if (episode.getScriptEpisodeId() != null && !episode.getScriptEpisodeId().equals(scriptScene.getEpisodeId())) {
            throw new IllegalArgumentException("剧本场次不属于分镜集关联的剧本分集");
        }
        return scriptScene;
    }

    private String resolveSceneHeading(String sceneHeading, ScriptSceneItem scriptScene) {
        if (sceneHeading != null && !sceneHeading.isBlank()) {
            return sceneHeading;
        }
        return scriptScene != null ? scriptScene.getSceneHeading() : null;
    }

    private List<Long> ids(JSONArray array) {
        if (array == null) return List.of();
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (Object value : array) {
            if (value == null) continue;
            values.add(value instanceof Number number ? number.longValue() : Long.valueOf(value.toString()));
        }
        return List.copyOf(values);
    }
}
