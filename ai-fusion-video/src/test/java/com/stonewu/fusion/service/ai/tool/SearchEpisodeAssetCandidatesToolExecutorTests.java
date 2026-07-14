package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.EpisodeAssetCandidateService;
import com.stonewu.fusion.service.asset.model.EpisodeAssetCandidate;
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
    @Mock
    private com.stonewu.fusion.service.asset.AssetService assetService;

    @InjectMocks
    private SearchEpisodeAssetCandidatesToolExecutor executor;

    @Test
    void executeReturnsOnlyTheCurrentEpisodeCandidatesAndTheirImageItems() {
        Asset asset = Asset.builder().id(10L).name("凌炽表情图").type("character").episodeNumber(1).build();
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).episodeNumber(1).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(candidateService.findCandidates(1L, 1, "character", "凌炽"))
                .thenReturn(List.of(new EpisodeAssetCandidate(asset, "suffix_normalized")));
        when(assetService.listItems(10L)).thenReturn(List.of(
                AssetItem.builder().id(11L).itemType("initial").imageUrl("https://example.test/lingjin.png").build()));

        String result = executor.execute("""
                {"projectId":1,"scriptEpisodeId":2,"assetType":"character","name":"凌炽"}
                """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getStr("matchStatus")).isEqualTo("unique");
        assertThat(json.getJSONArray("candidates")).singleElement().satisfies(rawCandidate -> {
            var candidate = JSONUtil.parseObj(rawCandidate);
            assertThat(candidate.getLong("assetId")).isEqualTo(10L);
            assertThat(candidate.getJSONArray("items").getJSONObject(0).getStr("imageUrl"))
                    .isEqualTo("https://example.test/lingjin.png");
        });
    }
}
