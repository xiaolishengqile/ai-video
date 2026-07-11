package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateImageToolExecutorTests {

    @Test
    void retriesThreeTimesAndReturnsImageWhenLaterAttemptSucceeds() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageGenerationConsumer imageGenerationConsumer = mock(ImageGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        AiModel imageModel = AiModel.builder().id(11L).name("Image Model").code("image-model").build();

        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        when(imageGenerationConsumer.submitAndWait(any(ImageTask.class), eq(30 * 60 * 1000L)))
                .thenThrow(new RuntimeException("temporary failure 1"))
                .thenThrow(new RuntimeException("temporary failure 2"))
                .thenThrow(new RuntimeException("temporary failure 3"))
                .thenReturn(ImageTask.builder().id(99L).build());
        when(imageGenerationService.listItems(99L)).thenReturn(List.of(
                ImageItem.builder().imageUrl("https://example.test/image.png").build()));

        GenerateImageToolExecutor executor = new GenerateImageToolExecutor(
                aiModelService, imageGenerationService, imageGenerationConsumer, capabilityService);

        String result = executor.execute("{\"prompt\":\"生成一张测试图\"}",
                ToolExecutionContext.builder().userId(7L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("success", json.getStr("status"));
        assertEquals("https://example.test/image.png", json.getStr("imageUrl"));
        assertEquals(4, json.getInt("attempts"));
        verify(imageGenerationConsumer, times(4)).submitAndWait(any(ImageTask.class), eq(30 * 60 * 1000L));
    }

    @Test
    void returnsErrorAfterThreeRetriesAreExhausted() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageGenerationConsumer imageGenerationConsumer = mock(ImageGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        AiModel imageModel = AiModel.builder().id(11L).name("Image Model").code("image-model").build();

        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        when(imageGenerationConsumer.submitAndWait(any(ImageTask.class), eq(30 * 60 * 1000L)))
                .thenThrow(new RuntimeException("still failing"));

        GenerateImageToolExecutor executor = new GenerateImageToolExecutor(
                aiModelService, imageGenerationService, imageGenerationConsumer, capabilityService);

        String result = executor.execute("{\"prompt\":\"生成一张测试图\"}",
                ToolExecutionContext.builder().userId(7L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("error", json.getStr("status"));
        assertTrue(json.getStr("message").contains("已重试3次"));
        verify(imageGenerationConsumer, times(4)).submitAndWait(any(ImageTask.class), eq(30 * 60 * 1000L));
    }
}
