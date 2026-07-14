package com.stonewu.fusion.service.ai.agentscope;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingEventHookTests {

    @Test
    void onlyTextBlocksArePublishedAsIncrementalDisplayEvents() {
        assertThat(StreamingEventHook.isDisplayableIncrementalBlock(
                TextBlock.builder().text("正文").build())).isTrue();
        assertThat(StreamingEventHook.isDisplayableIncrementalBlock(
                ThinkingBlock.builder().thinking("推理").build())).isFalse();
    }
}
