package com.stonewu.fusion.service.generation.consumer;

import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.infrastructure.queue.RedisTaskQueue;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.strategy.ImageGenerationStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationConsumerTests {

    @Test
    void shouldResolveGoogleFlowStrategyIgnoringPlatformCase() throws Exception {
        ImageGenerationStrategy vertexStrategy = mock(ImageGenerationStrategy.class);
        ImageGenerationStrategy flowStrategy = mock(ImageGenerationStrategy.class);
        Map<String, ImageGenerationStrategy> strategyMap = new LinkedHashMap<>();
        strategyMap.put("vertex_ai", vertexStrategy);
        strategyMap.put("GoogleFlowReverseApi", flowStrategy);

        ImageGenerationConsumer consumer = new ImageGenerationConsumer(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Method method = ImageGenerationConsumer.class.getDeclaredMethod(
                "resolveStrategyByPlatform",
                Map.class,
                String.class
        );
        method.setAccessible(true);

        ImageGenerationStrategy resolved = (ImageGenerationStrategy) method.invoke(
                consumer,
                strategyMap,
                "googleflowreverseapi"
        );

        assertSame(flowStrategy, resolved);
    }

    @Test
    void shouldReleaseStaleRunningSlotsWhenTaskAlreadyFinished() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageGenerationConsumer consumer = new ImageGenerationConsumer(
                taskQueue,
                imageGenerationService,
                null,
                null,
                null,
                List.of(),
                null
        );
        String queueName = "image_generation:model:2";

        ImageTask activeTask = ImageTask.builder()
                .taskId("active-task")
                .status(1)
                .build();
        activeTask.setUpdateTime(LocalDateTime.now());
        ImageTask staleTask = ImageTask.builder()
                .id(22L)
                .taskId("stale-task")
                .status(1)
                .build();
        staleTask.setUpdateTime(LocalDateTime.now().minusMinutes(40));

        when(taskQueue.listRunningTaskIds(queueName)).thenReturn(Set.of("failed-task", "active-task", "stale-task"));
        when(imageGenerationService.getByTaskId("failed-task")).thenReturn(ImageTask.builder()
                .taskId("failed-task")
                .status(3)
                .build());
        when(imageGenerationService.getByTaskId("active-task")).thenReturn(activeTask);
        when(imageGenerationService.getByTaskId("stale-task")).thenReturn(staleTask);
        when(taskQueue.getMaxConcurrent(queueName)).thenReturn(5);

        consumer.reconcileQueueLeases(queueName);

        verify(taskQueue).markComplete(queueName, "failed-task");
        verify(taskQueue).markComplete(queueName, "stale-task");
        verify(taskQueue, never()).markComplete(queueName, "active-task");
        verify(imageGenerationService).updateStatus(22L, 3, "图片任务处理超时，已释放队列槽位");
        verify(taskQueue).setConcurrentCount(queueName, 1);
    }
}
