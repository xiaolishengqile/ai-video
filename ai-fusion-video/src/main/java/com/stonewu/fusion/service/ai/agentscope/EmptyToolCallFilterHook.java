package com.stonewu.fusion.service.ai.agentscope;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 丢弃模型流中偶发生成的空参数幽灵工具调用。
 */
@Slf4j
public final class EmptyToolCallFilterHook implements Hook {

    private static final String RETRY_MESSAGE =
            "检测到工具调用缺少 required 参数。请根据任务上下文重新调用工具，并完整填写工具声明中的 required 参数。";

    private final Toolkit toolkit;

    public EmptyToolCallFilterHook(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostReasoningEvent reasoningEvent)) {
            return Mono.just(event);
        }

        Msg reasoningMessage = reasoningEvent.getReasoningMessage();
        if (reasoningMessage == null || reasoningMessage.getContent() == null) {
            return Mono.just(event);
        }

        List<ContentBlock> filteredContent = reasoningMessage.getContent().stream()
                .filter(block -> !isInvalidEmptyToolCall(block, reasoningEvent))
                .toList();
        if (filteredContent.size() == reasoningMessage.getContent().size()) {
            return Mono.just(event);
        }

        reasoningEvent.setReasoningMessage(copyWithContent(reasoningMessage, filteredContent));
        boolean hasExecutableToolCall = filteredContent.stream().anyMatch(ToolUseBlock.class::isInstance);
        if (!hasExecutableToolCall) {
            reasoningEvent.gotoReasoning(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(RETRY_MESSAGE)
                    .build());
        }
        return Mono.just(event);
    }

    private boolean isInvalidEmptyToolCall(ContentBlock block, PostReasoningEvent event) {
        if (!(block instanceof ToolUseBlock toolUse) || !isEmpty(toolUse.getInput())) {
            return false;
        }

        AgentTool tool = toolkit.getTool(toolUse.getName());
        if (tool == null || !hasRequiredParameters(tool.getParameters())) {
            return false;
        }

        log.warn("[EmptyToolCallFilterHook] 丢弃空参数工具调用: agent={}, tool={}, callId={}",
                event.getAgent().getName(), toolUse.getName(), toolUse.getId());
        return true;
    }

    private static boolean isEmpty(Map<String, Object> input) {
        return input == null || input.isEmpty();
    }

    private static boolean hasRequiredParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return false;
        }
        Object required = parameters.get("required");
        return required instanceof Collection<?> collection && !collection.isEmpty();
    }

    private static Msg copyWithContent(Msg message, List<ContentBlock> content) {
        return Msg.builder()
                .id(message.getId())
                .name(message.getName())
                .role(message.getRole())
                .content(content)
                .metadata(message.getMetadata())
                .timestamp(message.getTimestamp())
                .generateReason(message.getGenerateReason())
                .build();
    }
}
