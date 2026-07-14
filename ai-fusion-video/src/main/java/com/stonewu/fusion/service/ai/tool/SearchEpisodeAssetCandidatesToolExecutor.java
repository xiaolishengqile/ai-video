package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.asset.EpisodeAssetCandidateService;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Searches reusable assets strictly within one script episode. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchEpisodeAssetCandidatesToolExecutor implements ToolExecutor {

    private static final Set<String> ASSET_TYPES = Set.of("character", "scene", "prop");

    private final EpisodeAssetCandidateService candidateService;
    private final ProjectService projectService;
    private final ScriptService scriptService;
    private final AssetService assetService;

    @Override
    public String getToolName() {
        return "search_episode_asset_candidates";
    }

    @Override
    public String getDisplayName() {
        return "搜索本集可复用资产";
    }

    @Override
    public String getToolDescription() {
        return "按资产类型与名称，只在指定剧本分集所属的当前集搜索可复用资产。返回候选资产、匹配方式和子资产图片；不跨集搜索，不创建资产。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectId": { "type": "integer", "description": "项目 ID" },
                    "scriptEpisodeId": { "type": "integer", "description": "当前剧本分集 ID" },
                    "assetType": { "type": "string", "enum": ["character", "scene", "prop"] },
                    "name": { "type": "string", "description": "剧本实体名称，如凌炽" }
                  },
                  "required": ["projectId", "scriptEpisodeId", "assetType", "name"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            var params = JSONUtil.parseObj(toolInput);
            Long projectId = params.getLong("projectId");
            Long scriptEpisodeId = params.getLong("scriptEpisodeId");
            String assetType = params.getStr("assetType");
            String name = params.getStr("name");
            if (projectId == null || scriptEpisodeId == null || name == null || name.isBlank() || !ASSET_TYPES.contains(assetType)) {
                return error("缺少 projectId、scriptEpisodeId、有效 assetType 或 name");
            }
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(projectId, context.getUserId())) {
                return error("无权访问该项目");
            }
            var episode = scriptService.getEpisodeById(scriptEpisodeId);
            if (!scriptService.getById(episode.getScriptId()).getProjectId().equals(projectId)) {
                return error("scriptEpisodeId 不属于指定项目");
            }

            List<EpisodeAssetCandidate> candidates = candidateService.findCandidates(projectId, episode.getEpisodeNumber(), assetType, name);
            JSONArray result = new JSONArray();
            for (EpisodeAssetCandidate candidate : candidates) {
                JSONArray items = new JSONArray();
                for (AssetItem item : assetService.listItems(candidate.asset().getId())) {
                    items.add(JSONUtil.createObj()
                            .set("id", item.getId())
                            .set("name", item.getName())
                            .set("itemType", item.getItemType())
                            .set("imageUrl", item.getImageUrl())
                            .set("thumbnailUrl", item.getThumbnailUrl())
                            .set("hasImage", item.getImageUrl() != null && !item.getImageUrl().isBlank()));
                }
                result.add(JSONUtil.createObj()
                        .set("assetId", candidate.asset().getId())
                        .set("name", candidate.asset().getName())
                        .set("type", candidate.asset().getType())
                        .set("episodeNumber", candidate.asset().getEpisodeNumber())
                        .set("matchMode", candidate.matchMode())
                        .set("items", items));
            }
            return JSONUtil.createObj()
                    .set("episodeNumber", episode.getEpisodeNumber())
                    .set("matchStatus", candidates.isEmpty() ? "none" : candidates.size() == 1 ? "unique" : "ambiguous")
                    .set("candidates", result)
                    .toString();
        } catch (Exception e) {
            log.warn("搜索本集资产候选失败", e);
            return error("搜索失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
