package com.stonewu.fusion.service.storyboard;

import com.stonewu.fusion.controller.storyboard.vo.StoryboardGridImageGenerateReqVO;
import com.stonewu.fusion.controller.storyboard.vo.StoryboardWorkflowUpdateReqVO;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardGridImageGenerationServiceTests {

    @Mock
    private StoryboardService storyboardService;

    @Mock
    private AssetService assetService;

    @Mock
    private ImageGenerationConsumer imageGenerationConsumer;

    @Mock
    private ImageGenerationService imageGenerationService;

    private StoryboardGridImageGenerationService service;

    @BeforeEach
    void setUp() {
        service = new StoryboardGridImageGenerationService(
                storyboardService,
                assetService,
                imageGenerationConsumer,
                imageGenerationService
        );
        ReflectionTestUtils.setField(service, "gridImageExecutor", (Executor) Runnable::run);
    }

    @Test
    void submitGenerateUsesExistingPromptAndAssetReferences() throws Exception {
        StoryboardItem item = StoryboardItem.builder()
                .id(101L)
                .storyboardId(11L)
                .videoWorkflowMode("narrative")
                .grid25Prompt("生成 25 宫格")
                .firstFrameImageUrl("https://cdn.test/first.png")
                .characterIds("[301]")
                .build();
        when(storyboardService.getById(11L)).thenReturn(Storyboard.builder().id(11L).projectId(22L).build());
        when(storyboardService.listItems(11L)).thenReturn(List.of(item));
        when(storyboardService.getItemById(101L)).thenReturn(item);
        when(assetService.getItemById(301L)).thenReturn(AssetItem.builder()
                .id(301L)
                .imageUrl("https://cdn.test/character.png")
                .build());
        when(imageGenerationConsumer.submitAndWait(any(ImageTask.class), eq(1_800_000L)))
                .thenReturn(ImageTask.builder().id(501L).build());
        when(imageGenerationService.listItems(501L)).thenReturn(List.of(
                ImageItem.builder().imageUrl("https://cdn.test/grid25.png").build()
        ));

        StoryboardGridImageGenerateReqVO reqVO = new StoryboardGridImageGenerateReqVO();
        reqVO.setStoryboardItemIds(List.of(101L));
        var resp = service.submitGenerate(11L, reqVO, 9L);

        assertThat(resp.getSubmittedCount()).isEqualTo(1);
        ArgumentCaptor<ImageTask> taskCaptor = ArgumentCaptor.forClass(ImageTask.class);
        verify(imageGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(1_800_000L));
        assertThat(taskCaptor.getValue().getProjectId()).isEqualTo(22L);
        assertThat(taskCaptor.getValue().getPrompt()).isEqualTo("生成 25 宫格");
        assertThat(taskCaptor.getValue().getRefImageUrls()).contains("first.png", "character.png");
        assertThat(taskCaptor.getValue().getWidth()).isEqualTo(2048);
        assertThat(taskCaptor.getValue().getHeight()).isEqualTo(1152);

        ArgumentCaptor<StoryboardWorkflowUpdateReqVO> updateCaptor =
                ArgumentCaptor.forClass(StoryboardWorkflowUpdateReqVO.class);
        verify(storyboardService).updateItemWorkflow(eq(101L), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getVideoWorkflowResolvedMode()).isEqualTo("narrative");
        assertThat(updateCaptor.getValue().getGrid25ImageUrl()).isEqualTo("https://cdn.test/grid25.png");
    }

    @Test
    void submitGenerateSkipsGeneratedAndMissingPromptItems() {
        StoryboardItem generated = StoryboardItem.builder()
                .id(101L)
                .storyboardId(11L)
                .videoWorkflowMode("action")
                .actionStoryboardPrompt("生成 4 宫格")
                .actionStoryboardImageUrl("https://cdn.test/action.png")
                .build();
        StoryboardItem missingPrompt = StoryboardItem.builder()
                .id(102L)
                .storyboardId(11L)
                .videoWorkflowMode("narrative")
                .build();
        when(storyboardService.getById(11L)).thenReturn(Storyboard.builder().id(11L).projectId(22L).build());
        when(storyboardService.listItems(11L)).thenReturn(List.of(generated, missingPrompt));

        var resp = service.submitGenerate(11L, new StoryboardGridImageGenerateReqVO(), 9L);

        assertThat(resp.getSubmittedCount()).isZero();
        assertThat(resp.getSkippedGeneratedCount()).isEqualTo(1);
        assertThat(resp.getSkippedMissingPromptCount()).isEqualTo(1);
        verifyNoInteractions(imageGenerationConsumer, imageGenerationService);
        verify(storyboardService, never()).updateItemWorkflow(any(), any());
    }
}
