package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.EpisodeAssetCandidateService;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
import com.stonewu.fusion.service.asset.model.EpisodeAssetSearchResult;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchEpisodeAssetCandidatesToolExecutorTests {

    @Mock
    private EpisodeAssetCandidateService candidateService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScriptService scriptService;
    @InjectMocks
    private SearchEpisodeAssetCandidatesToolExecutor executor;

    @Test
    void executeKeepsSingleQueryCompatibleAndReturnsCompactEvidence() {
        Asset asset = Asset.builder().id(10L).name("凌炽表情图").type("character").episodeNumber(1).build();
        AssetItem item = AssetItem.builder().id(11L).itemType("initial")
                .imageUrl("https://example.test/lingjin.png").build();
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).episodeNumber(1).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(candidateService.search(1L, 1, "character", "凌炽"))
                .thenReturn(new EpisodeAssetSearchResult("unique", 90, List.of(
                        new EpisodeAssetCandidate(asset, "visual_suffix", 90, "凌炽表情图", "去除视觉后缀后相同", item))));

        String result = executor.execute("""
                {"projectId":1,"scriptEpisodeId":2,"assetType":"character","name":"凌炽"}
                """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getStr("queryName")).isEqualTo("凌炽");
        assertThat(json.getStr("assetType")).isEqualTo("character");
        assertThat(json.getStr("matchStatus")).isEqualTo("unique");
        assertThat(json.getInt("bestScore")).isEqualTo(90);
        assertThat(json.getJSONArray("candidates")).singleElement().satisfies(rawCandidate -> {
            var candidate = JSONUtil.parseObj(rawCandidate);
            assertThat(candidate.getLong("assetId")).isEqualTo(10L);
            assertThat(candidate.getInt("score")).isEqualTo(90);
            assertThat(candidate.getStr("matchedName")).isEqualTo("凌炽表情图");
            assertThat(candidate.getStr("evidence")).contains("视觉后缀");
            assertThat(candidate.getLong("assetItemId")).isEqualTo(11L);
            assertThat(candidate.getStr("imageUrl")).isEqualTo("https://example.test/lingjin.png");
            assertThat(candidate.containsKey("items")).isFalse();
        });
    }

    @Test
    void executeSearchesSeveralEpisodeEntitiesInOneBatch() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).episodeNumber(13).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(candidateService.search(1L, 13, "prop", "地下异常热源"))
                .thenReturn(new EpisodeAssetSearchResult("none", 0, List.of()));
        when(candidateService.search(1L, 13, "scene", "补给站"))
                .thenReturn(new EpisodeAssetSearchResult("none", 0, List.of()));

        String result = executor.execute("""
                {"projectId":1,"scriptEpisodeId":2,"queries":[
                  {"assetType":"prop","name":"地下异常热源"},
                  {"assetType":"scene","name":"补给站"}
                ]}
                """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getInt("episodeNumber")).isEqualTo(13);
        assertThat(json.getJSONArray("results")).hasSize(2);
        assertThat(json.getJSONArray("results").getJSONObject(0).getStr("queryName"))
                .isEqualTo("地下异常热源");
    }
}
