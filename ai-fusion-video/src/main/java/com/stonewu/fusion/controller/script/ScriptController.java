package com.stonewu.fusion.controller.script;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.controller.script.vo.EpisodeCreateReqVO;
import com.stonewu.fusion.controller.script.vo.EpisodeUpdateReqVO;
import com.stonewu.fusion.controller.script.vo.SceneCreateReqVO;
import com.stonewu.fusion.controller.script.vo.SceneUpdateReqVO;
import com.stonewu.fusion.controller.script.vo.ScriptAssetBindingReviewReqVO;
import com.stonewu.fusion.controller.script.vo.ScriptAssetBindingRunReqVO;
import com.stonewu.fusion.controller.script.vo.ScriptCreateReqVO;
import com.stonewu.fusion.controller.script.vo.ScriptUpdateReqVO;
import com.stonewu.fusion.convert.script.ScriptConvert;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.security.SecurityUtils;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptAssetPrebindingService;
import com.stonewu.fusion.service.script.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剧本 Controller（含分集、分场次）
 */
@Tag(name = "剧本管理")
@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final ScriptAssetPrebindingService prebindingService;
    private final ProjectService projectService;

    // ========== 剧本 ==========

    @Operation(summary = "获取剧本详情")
    @GetMapping("/{id}")
    public CommonResult<Script> get(@PathVariable Long id) {
        return CommonResult.success(scriptService.getById(id));
    }

    @Operation(summary = "按项目查询剧本列表")
    @GetMapping("/list")
    public CommonResult<List<Script>> list(@RequestParam Long projectId) {
        return CommonResult.success(scriptService.listByProject(projectId));
    }

    @Operation(summary = "创建剧本")
    @PostMapping
    public CommonResult<Script> create(@Valid @RequestBody ScriptCreateReqVO reqVO) {
        Script script = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.create(script));
    }

    @Operation(summary = "更新剧本")
    @PutMapping
    public CommonResult<Script> update(@Valid @RequestBody ScriptUpdateReqVO reqVO) {
        Script script = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.update(script));
    }

    @Operation(summary = "删除剧本")
    @DeleteMapping("/{id}")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        scriptService.delete(id);
        return CommonResult.success(true);
    }

    // ========== 分集 ==========

    @Operation(summary = "获取分集列表")
    @GetMapping("/{scriptId}/episodes")
    public CommonResult<List<ScriptEpisode>> listEpisodes(@PathVariable Long scriptId) {
        return CommonResult.success(scriptService.listEpisodes(scriptId));
    }

    @Operation(summary = "获取分集详情")
    @GetMapping("/episode/{id}")
    public CommonResult<ScriptEpisode> getEpisode(@PathVariable Long id) {
        return CommonResult.success(scriptService.getEpisodeById(id));
    }

    @Operation(summary = "运行分集资产预匹配")
    @PostMapping("/asset-bindings/run")
    public CommonResult<ScriptAssetPrebindingService.PrebindingSummary> runAssetPrebinding(
            @Valid @RequestBody ScriptAssetBindingRunReqVO reqVO) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null && !projectService.canAccessProject(reqVO.getProjectId(), userId)) {
            return CommonResult.error(403, "没有权限访问");
        }
        return CommonResult.success(prebindingService.runEpisodePrebinding(
                reqVO.getProjectId(), reqVO.getScriptId(), reqVO.getScriptEpisodeId()));
    }

    @Operation(summary = "查询分集资产预匹配结果")
    @GetMapping("/asset-bindings")
    public CommonResult<List<ScriptAssetBinding>> listAssetBindings(@RequestParam Long scriptEpisodeId) {
        if (!canAccessEpisode(scriptEpisodeId)) {
            return CommonResult.error(403, "没有权限访问");
        }
        return CommonResult.success(prebindingService.listBindings(scriptEpisodeId));
    }

    @Operation(summary = "确认资产预匹配结果")
    @PutMapping("/asset-bindings/{id}/review")
    public CommonResult<ScriptAssetBinding> reviewAssetBinding(@PathVariable Long id,
            @RequestBody ScriptAssetBindingReviewReqVO reqVO) {
        ScriptAssetBinding existing = prebindingService.getBinding(id);
        if (existing == null || !canAccessEpisode(existing.getScriptEpisodeId())) {
            return CommonResult.error(403, "没有权限访问");
        }
        return CommonResult.success(prebindingService.reviewBinding(id, reqVO.getAssetId(), reqVO.getAssetItemId(),
                reqVO.getMatchStatus(), reqVO.getMatchSource(), reqVO.getReviewed()));
    }

    @Operation(summary = "创建分集")
    @PostMapping("/episode")
    public CommonResult<ScriptEpisode> createEpisode(@Valid @RequestBody EpisodeCreateReqVO reqVO) {
        ScriptEpisode episode = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.createEpisode(episode));
    }

    @Operation(summary = "更新分集")
    @PutMapping("/episode")
    public CommonResult<ScriptEpisode> updateEpisode(@Valid @RequestBody EpisodeUpdateReqVO reqVO) {
        ScriptEpisode episode = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.updateEpisode(episode));
    }

    @Operation(summary = "删除分集")
    @DeleteMapping("/episode/{id}")
    public CommonResult<Boolean> deleteEpisode(@PathVariable Long id) {
        scriptService.deleteEpisode(id);
        return CommonResult.success(true);
    }

    // ========== 分场次 ==========

    @Operation(summary = "获取分场次列表（按分集）")
    @GetMapping("/episode/{episodeId}/scenes")
    public CommonResult<List<ScriptSceneItem>> listScenes(@PathVariable Long episodeId) {
        return CommonResult.success(scriptService.listScenesByEpisode(episodeId));
    }

    @Operation(summary = "获取分场次详情")
    @GetMapping("/scene/{id}")
    public CommonResult<ScriptSceneItem> getScene(@PathVariable Long id) {
        return CommonResult.success(scriptService.getSceneById(id));
    }

    @Operation(summary = "创建分场次")
    @PostMapping("/scene")
    public CommonResult<ScriptSceneItem> createScene(@Valid @RequestBody SceneCreateReqVO reqVO) {
        ScriptSceneItem scene = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.createScene(scene));
    }

    @Operation(summary = "更新分场次")
    @PutMapping("/scene")
    public CommonResult<ScriptSceneItem> updateScene(@Valid @RequestBody SceneUpdateReqVO reqVO) {
        ScriptSceneItem scene = ScriptConvert.INSTANCE.convert(reqVO);
        return CommonResult.success(scriptService.updateScene(scene));
    }

    @Operation(summary = "删除分场次")
    @DeleteMapping("/scene/{id}")
    public CommonResult<Boolean> deleteScene(@PathVariable Long id) {
        scriptService.deleteScene(id);
        return CommonResult.success(true);
    }

    private boolean canAccessEpisode(Long scriptEpisodeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return true;
        }
        ScriptEpisode episode = scriptService.getEpisodeById(scriptEpisodeId);
        if (episode == null) {
            return false;
        }
        Script script = scriptService.getById(episode.getScriptId());
        return script != null && projectService.canAccessProject(script.getProjectId(), userId);
    }
}
