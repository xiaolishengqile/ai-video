package com.stonewu.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.script.ScriptEpisodeMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardItemMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.stonewu.fusion.service.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardServiceTests {

    @Mock
    private StoryboardMapper storyboardMapper;

    @Mock
    private StoryboardEpisodeMapper episodeMapper;

    @Mock
    private StoryboardSceneMapper sceneMapper;

    @Mock
    private StoryboardItemMapper itemMapper;

    @Mock
    private ScriptEpisodeMapper scriptEpisodeMapper;

    @Mock
    private TeamService teamService;

    private StoryboardService storyboardService;

    @BeforeEach
    void setUp() {
        storyboardService = new StoryboardService(
                storyboardMapper,
                episodeMapper,
                sceneMapper,
                itemMapper,
                scriptEpisodeMapper,
                teamService
        );
    }

    @Test
    void bindScriptEpisodeRejectsCrossScriptEpisode() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .build());
        when(storyboardMapper.selectById(21L)).thenReturn(Storyboard.builder()
                .id(21L)
                .scriptId(31L)
                .build());
        when(scriptEpisodeMapper.selectById(41L)).thenReturn(ScriptEpisode.builder()
                .id(41L)
                .scriptId(99L)
                .build());

        assertThatThrownBy(() -> storyboardService.bindScriptEpisode(11L, 41L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("剧本分集不属于当前分镜关联的剧本");

        verify(episodeMapper, never()).updateById(any(StoryboardEpisode.class));
    }

    @Test
    void saveEpisodeForScriptReusesExistingBinding() {
        StoryboardEpisode existing = StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .scriptEpisodeId(41L)
                .episodeNumber(1)
                .build();
        StoryboardEpisode updated = StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .scriptEpisodeId(41L)
                .episodeNumber(1)
                .title("第一集")
                .build();

        when(storyboardMapper.selectById(21L)).thenReturn(Storyboard.builder()
                .id(21L)
                .scriptId(31L)
                .build());
        when(scriptEpisodeMapper.selectById(41L)).thenReturn(ScriptEpisode.builder()
                .id(41L)
                .scriptId(31L)
                .episodeNumber(1)
                .title("第一集")
                .build());
        when(episodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(episodeMapper.selectById(11L)).thenReturn(updated);

        StoryboardEpisode result = storyboardService.saveEpisodeForScript(
                21L, 41L, 1, "第一集", "梗概");

        assertThat(result.getId()).isEqualTo(11L);
        verify(episodeMapper).updateById(any(StoryboardEpisode.class));
        verify(episodeMapper, never()).insert(any(StoryboardEpisode.class));
    }

    @Test
    void clearEpisodeContentDeletesOnlyTargetEpisodeScenesAndItems() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder()
                .id(11L)
                .storyboardId(21L)
                .build());
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                StoryboardScene.builder().id(101L).episodeId(11L).build(),
                StoryboardScene.builder().id(102L).episodeId(11L).build()
        ));

        storyboardService.clearEpisodeContent(11L);

        verify(itemMapper).delete(any(LambdaQueryWrapper.class));
        verify(sceneMapper).delete(any(LambdaQueryWrapper.class));
    }
}
