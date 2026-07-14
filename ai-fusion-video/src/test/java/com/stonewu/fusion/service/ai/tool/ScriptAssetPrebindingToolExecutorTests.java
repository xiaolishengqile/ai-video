package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.script.ScriptAssetPrebindingService;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptAssetPrebindingToolExecutorTests {

    @Mock
    private ScriptAssetPrebindingService prebindingService;
    @Mock
    private ScriptService scriptService;
    @Mock
    private ProjectService projectService;

    @Test
    void runToolReturnsPrebindingSummary() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(prebindingService.runEpisodePrebinding(1L, 3L, 2L))
                .thenReturn(new ScriptAssetPrebindingService.PrebindingSummary(2, 0, 1, 0, 3));

        String result = new RunScriptAssetPrebindingToolExecutor(prebindingService, projectService)
                .execute("""
                        {"projectId":1,"scriptId":3,"scriptEpisodeId":2}
                        """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getStr("status")).isEqualTo("success");
        assertThat(json.getInt("matched")).isEqualTo(2);
        assertThat(json.getInt("ambiguous")).isEqualTo(1);
        assertThat(json.getInt("uploadedUnused")).isEqualTo(3);
    }

    @Test
    void runToolRetriesDeadlockOnce() {
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(prebindingService.runEpisodePrebinding(1L, 3L, 2L))
                .thenThrow(new RuntimeException("Deadlock found when trying to get lock"))
                .thenReturn(new ScriptAssetPrebindingService.PrebindingSummary(1, 0, 0, 0, 4));

        String result = new RunScriptAssetPrebindingToolExecutor(prebindingService, projectService)
                .execute("""
                        {"projectId":1,"scriptId":3,"scriptEpisodeId":2}
                        """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getStr("status")).isEqualTo("success");
        assertThat(json.getInt("matched")).isEqualTo(1);
        verify(prebindingService, times(2)).runEpisodePrebinding(1L, 3L, 2L);
    }

    @Test
    void listToolReturnsReviewedBindingsForTheEpisode() {
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder().id(2L).scriptId(3L).build());
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
        when(prebindingService.listBindings(2L)).thenReturn(List.of(ScriptAssetBinding.builder()
                .id(100L)
                .entityName("顾沉舟")
                .assetType("character")
                .assetId(10L)
                .assetItemId(11L)
                .matchStatus("matched")
                .matchSource("exact_name")
                .confidence(100)
                .reviewed(true)
                .build()));

        String result = new ListScriptAssetBindingsToolExecutor(prebindingService, scriptService, projectService)
                .execute("""
                        {"scriptEpisodeId":2}
                        """, ToolExecutionContext.builder().userId(9L).build());

        var json = JSONUtil.parseObj(result);
        assertThat(json.getStr("status")).isEqualTo("success");
        assertThat(json.getJSONArray("bindings")).singleElement().satisfies(raw -> {
            var binding = JSONUtil.parseObj(raw);
            assertThat(binding.getStr("entityName")).isEqualTo("顾沉舟");
            assertThat(binding.getLong("assetItemId")).isEqualTo(11L);
        });
    }
}
