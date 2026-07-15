package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.EpisodeAssetCandidateService;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import com.stonewu.fusion.service.asset.model.EpisodeAssetSearchResult;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
                    "name": { "type": "string", "description": "单条查询的剧本实体名称，如凌炽" },
                    "queries": {
                      "type": "array",
                      "description": "同一集批量查询；提供后忽略单条 name/assetType",
                      "items": {
                        "type": "object",
                        "properties": {
                          "assetType": { "type": "string", "enum": ["character", "scene", "prop"] },
                          "name": { "type": "string" }
                        },
                        "required": ["assetType", "name"]
                      }
                    }
                  },
                  "required": ["projectId", "scriptEpisodeId"]
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
            JSONArray queries = params.getJSONArray("queries");
            boolean batch = queries != null && !queries.isEmpty();
            if (projectId == null || scriptEpisodeId == null || (!batch && !validQuery(assetType, name))) {
                return error("缺少 projectId、scriptEpisodeId 或有效查询条件");
            }
            if (context == null || context.getUserId() == null || !projectService.canAccessProject(projectId, context.getUserId())) {
                return error("无权访问该项目");
            }
            var episode = scriptService.getEpisodeById(scriptEpisodeId);
            if (!scriptService.getById(episode.getScriptId()).getProjectId().equals(projectId)) {
                return error("scriptEpisodeId 不属于指定项目");
            }

            if (batch) {
                JSONArray results = new JSONArray();
                for (Object value : queries) {
                    JSONObject query = JSONUtil.parseObj(value);
                    String queryType = query.getStr("assetType");
                    String queryName = query.getStr("name");
                    if (!validQuery(queryType, queryName)) {
                        return error("批量查询包含无效 assetType 或 name");
                    }
                    results.add(search(projectId, episode.getEpisodeNumber(), queryType, queryName));
                }
                return JSONUtil.createObj()
                        .set("episodeNumber", episode.getEpisodeNumber())
                        .set("results", results)
                        .toString();
            }
            return search(projectId, episode.getEpisodeNumber(), assetType, name).toString();
        } catch (Exception e) {
            log.warn("搜索本集资产候选失败", e);
            return error("搜索失败: " + e.getMessage());
        }
    }

    private JSONObject search(Long projectId, Integer episodeNumber, String assetType, String name) {
        EpisodeAssetSearchResult search = candidateService.search(projectId, episodeNumber, assetType, name);
        JSONArray candidates = new JSONArray();
        for (EpisodeAssetCandidate candidate : search.candidates()) {
            candidates.add(JSONUtil.createObj()
                    .set("assetId", candidate.asset().getId())
                    .set("name", candidate.asset().getName())
                    .set("type", candidate.asset().getType())
                    .set("episodeNumber", candidate.asset().getEpisodeNumber())
                    .set("score", candidate.score())
                    .set("matchMode", candidate.matchMode())
                    .set("matchedName", candidate.matchedName())
                    .set("evidence", candidate.evidence())
                    .set("assetItemId", candidate.assetItem().getId())
                    .set("imageUrl", candidate.assetItem().getImageUrl())
                    .set("thumbnailUrl", candidate.assetItem().getThumbnailUrl()));
        }
        return JSONUtil.createObj()
                .set("episodeNumber", episodeNumber)
                .set("queryName", name)
                .set("assetType", assetType)
                .set("matchStatus", search.matchStatus())
                .set("bestScore", search.bestScore())
                .set("reason", "none".equals(search.matchStatus()) ? "当前集当前类型没有达到阈值且有图的候选" : null)
                .set("candidates", candidates);
    }

    private boolean validQuery(String assetType, String name) {
        return ASSET_TYPES.contains(assetType) && name != null && !name.isBlank();
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
