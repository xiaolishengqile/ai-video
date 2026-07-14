package com.stonewu.fusion.service.ai.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmptyToolCallFilterHookTests {

    @Test
    void removesEmptyRequiredToolCallButKeepsValidCall() {
        Toolkit toolkit = toolkitWith(
                tool("save_script_episode", List.of("scriptId", "episodeNumber", "title")));
        PostReasoningEvent event = eventWith(
                TextBlock.builder().text("开始保存").build(),
                toolCall("ghost", "save_script_episode", Map.of()),
                toolCall("valid", "save_script_episode", Map.of(
                        "scriptId", 36,
                        "episodeNumber", 2,
                        "title", "第二集")));

        new EmptyToolCallFilterHook(toolkit).onEvent(event).block();

        assertThat(event.getReasoningMessage().getContent())
                .extracting(ContentBlock::getClass)
                .containsExactly(TextBlock.class, ToolUseBlock.class);
        assertThat(event.getReasoningMessage().getContentBlocks(ToolUseBlock.class))
                .extracting(ToolUseBlock::getId)
                .containsExactly("valid");
        assertThat(event.isGotoReasoningRequested()).isFalse();
    }

    @Test
    void retriesReasoningWhenAllToolCallsAreEmptyAndRequireParameters() {
        Toolkit toolkit = toolkitWith(tool("update_script_info", List.of("scriptId")));
        PostReasoningEvent event = eventWith(toolCall("ghost", "update_script_info", Map.of()));

        new EmptyToolCallFilterHook(toolkit).onEvent(event).block();

        assertThat(event.getReasoningMessage().getContentBlocks(ToolUseBlock.class)).isEmpty();
        assertThat(event.isGotoReasoningRequested()).isTrue();
        assertThat(event.getGotoReasoningMsgs())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getRole()).isEqualTo(MsgRole.USER);
                    assertThat(message.getTextContent()).contains("required");
                });
    }

    @Test
    void keepsEmptyCallForToolWithoutRequiredParameters() {
        Toolkit toolkit = toolkitWith(tool("list_models", List.of()));
        PostReasoningEvent event = eventWith(toolCall("valid-zero-arg", "list_models", Map.of()));

        new EmptyToolCallFilterHook(toolkit).onEvent(event).block();

        assertThat(event.getReasoningMessage().getContentBlocks(ToolUseBlock.class))
                .extracting(ToolUseBlock::getId)
                .containsExactly("valid-zero-arg");
        assertThat(event.isGotoReasoningRequested()).isFalse();
    }

    private static PostReasoningEvent eventWith(ContentBlock... blocks) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("script_full_parse");
        Msg reasoningMessage = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(blocks))
                .build();
        return new PostReasoningEvent(agent, "test-model", null, reasoningMessage);
    }

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input)
                .build();
    }

    private static Toolkit toolkitWith(AgentTool... tools) {
        Toolkit toolkit = new Toolkit();
        for (AgentTool tool : tools) {
            toolkit.registerAgentTool(tool);
        }
        return toolkit;
    }

    private static AgentTool tool(String name, List<String> required) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test tool";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", required);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
    }
}
