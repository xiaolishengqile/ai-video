package com.stonewu.fusion.service.ai;

import com.stonewu.fusion.controller.ai.vo.AiChatStreamRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiStreamRedisServiceTests {

    @Test
    void replayListIsTrimmedAfterEachAppend() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        AiStreamRedisService service = new AiStreamRedisService(redisTemplate);

        service.appendReplayEvent("conversation-1",
                new AiChatStreamRespVO().setOutputType("CONTENT").setContent("hello"),
                "1-0");

        verify(listOperations).rightPush(eq("fv:ai:stream:replay:conversation-1"), anyString());
        verify(listOperations).trim("fv:ai:stream:replay:conversation-1", -2000, -1);
    }

    @Test
    void mainAgentTerminalEventOnlyMatchesConversationLevelTerminals() {
        AiChatStreamRespVO mainDone = new AiChatStreamRespVO().setOutputType("DONE");
        AiChatStreamRespVO mainError = new AiChatStreamRespVO().setOutputType("ERROR");
        AiChatStreamRespVO mainCancelled = new AiChatStreamRespVO().setOutputType("CANCELLED");
        AiChatStreamRespVO subAgentError = new AiChatStreamRespVO()
                .setOutputType("ERROR")
                .setAgentName("episode_storyboard_writer")
                .setParentToolCallId("call_1");
        AiChatStreamRespVO unmappedSubAgentError = new AiChatStreamRespVO()
                .setOutputType("ERROR")
                .setAgentName("episode_storyboard_writer");

        assertThat(AiStreamRedisService.isMainAgentTerminalEvent(mainDone)).isTrue();
        assertThat(AiStreamRedisService.isMainAgentTerminalEvent(mainError)).isTrue();
        assertThat(AiStreamRedisService.isMainAgentTerminalEvent(mainCancelled)).isTrue();
        assertThat(AiStreamRedisService.isMainAgentTerminalEvent(subAgentError)).isFalse();
        assertThat(AiStreamRedisService.isMainAgentTerminalEvent(unmappedSubAgentError)).isFalse();
    }

    @Test
    void mainAgentErrorAndCancelledIgnoreSubAgentEvents() {
        AiChatStreamRespVO mainError = new AiChatStreamRespVO().setOutputType("ERROR");
        AiChatStreamRespVO mainCancelled = new AiChatStreamRespVO().setOutputType("CANCELLED");
        AiChatStreamRespVO subAgentError = new AiChatStreamRespVO()
                .setOutputType("ERROR")
                .setAgentName("episode_storyboard_writer")
                .setParentToolCallId("call_1");
        AiChatStreamRespVO subAgentCancelled = new AiChatStreamRespVO()
                .setOutputType("CANCELLED")
                .setAgentName("episode_storyboard_writer")
                .setParentToolCallId("call_1");

        assertThat(AiStreamRedisService.isMainAgentErrorEvent(mainError)).isTrue();
        assertThat(AiStreamRedisService.isMainAgentCancelledEvent(mainCancelled)).isTrue();
        assertThat(AiStreamRedisService.isMainAgentErrorEvent(subAgentError)).isFalse();
        assertThat(AiStreamRedisService.isMainAgentCancelledEvent(subAgentCancelled)).isFalse();
    }
}
