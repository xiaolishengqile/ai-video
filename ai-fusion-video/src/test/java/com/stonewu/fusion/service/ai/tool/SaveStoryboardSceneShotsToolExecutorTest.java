package com.stonewu.fusion.service.ai.tool;

import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.script.ScriptSceneItemMapper;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaveStoryboardSceneShotsToolExecutorTest {

    @Test
    void usesScriptSceneHeadingWhenAiOmitsSceneHeading() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        ScriptSceneItemMapper scriptSceneItemMapper = mock(ScriptSceneItemMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService, scriptSceneItemMapper, projectService);

        when(storyboardService.getById(24L)).thenReturn(Storyboard.builder().id(24L).projectId(17L).build());
        when(projectService.canAccessProject(17L, 1L)).thenReturn(true);
        when(storyboardService.getEpisodeById(122L)).thenReturn(StoryboardEpisode.builder()
                .id(122L)
                .storyboardId(24L)
                .scriptEpisodeId(284L)
                .build());
        when(scriptSceneItemMapper.selectById(579L)).thenReturn(ScriptSceneItem.builder()
                .id(579L)
                .episodeId(284L)
                .sceneHeading("1-1 三十七号旧战场 夜 外景")
                .build());
        when(storyboardService.createScene(any(StoryboardScene.class))).thenAnswer(invocation -> {
            StoryboardScene scene = invocation.getArgument(0);
            scene.setId(900L);
            return scene;
        });

        String input = """
                {
                  "storyboardId": 24,
                  "storyboardEpisodeId": 122,
                  "scriptSceneItemId": 579,
                  "sceneNumber": "1-1",
                  "location": "三十七号旧战场",
                  "timeOfDay": "夜",
                  "intExt": "外景",
                  "shots": [
                    { "content": "旧战场夜空被撕裂", "duration": 15 }
                  ]
                }
                """;

        executor.execute(input, ToolExecutionContext.builder().userId(1L).build());

        ArgumentCaptor<StoryboardScene> sceneCaptor = ArgumentCaptor.forClass(StoryboardScene.class);
        verify(storyboardService).createScene(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getSceneHeading()).isEqualTo("1-1 三十七号旧战场 夜 外景");
    }
}
