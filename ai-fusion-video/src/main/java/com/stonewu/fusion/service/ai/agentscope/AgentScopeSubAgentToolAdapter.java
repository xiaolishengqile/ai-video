package com.stonewu.fusion.service.ai.agentscope;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDecision;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDescriptor;
import com.stonewu.fusion.service.ai.pipeline.PipelineExecutionContext;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointPolicyRegistry;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * AgentScope 子 Agent 工具适配器。
 * <p>
 * 与 AgentScope 默认 SubAgentTool 不同，这里在 callAsync 中能拿到当前 ToolCallParam，
 * 因此可以把新创建的子 Agent 实例和父 tool_call_id 精确绑定，支持同名子 Agent 并行执行。
 */
@Slf4j
public class AgentScopeSubAgentToolAdapter implements AgentTool {

    private final String toolName;
    private final String description;
    private final String parametersSchema;
    private final Supplier<ReActAgent> agentFactory;
    private final StreamingEventHook streamingHook;
    private final AgentCancellationToken cancellationToken;
    private final PipelineExecutionContext pipelineContext;
    private final PipelineToolCheckpointPolicyRegistry checkpointPolicies;
    private final PipelineToolCheckpointService checkpoints;

    public AgentScopeSubAgentToolAdapter(String toolName,
            String description,
            String parametersSchema,
            Supplier<ReActAgent> agentFactory,
            StreamingEventHook streamingHook,
            AgentCancellationToken cancellationToken) {
        this(toolName, description, parametersSchema, agentFactory, streamingHook, cancellationToken,
                null, null, null);
    }

    public AgentScopeSubAgentToolAdapter(
            String toolName,
            String description,
            String parametersSchema,
            Supplier<ReActAgent> agentFactory,
            StreamingEventHook streamingHook,
            AgentCancellationToken cancellationToken,
            PipelineExecutionContext pipelineContext,
            PipelineToolCheckpointPolicyRegistry checkpointPolicies,
            PipelineToolCheckpointService checkpoints) {
        this.toolName = toolName;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.agentFactory = agentFactory;
        this.streamingHook = streamingHook;
        this.cancellationToken = cancellationToken;
        this.pipelineContext = pipelineContext;
        this.checkpointPolicies = checkpointPolicies;
        this.checkpoints = checkpoints;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        if (parametersSchema != null && !parametersSchema.isBlank()) {
            try {
                JSONObject schema = JSONUtil.parseObj(parametersSchema);
                if (!schema.isEmpty()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    schema.forEach(result::put);
                    return result;
                }
            } catch (Exception e) {
                log.warn("[AgentScopeSubAgentToolAdapter] 子 Agent 参数 schema 无效，回退 message: name={}", toolName, e);
            }
        }
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "发送给子 Agent 的任务消息")),
                "required", List.of("message"),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            cancellationToken.throwIfCancelled();

            String input = param == null || param.getInput() == null
                    ? "{}"
                    : JSONUtil.toJsonStr(param.getInput());
            CheckpointDescriptor descriptor = null;
            if (pipelineContext != null) {
                Optional<CheckpointDescriptor> described = checkpointPolicies.describe(getName(), input);
                if (described.isEmpty()) {
                    return Mono.just(buildToolResult(param, manualResult("子 Agent 未配置检查点策略")));
                }
                descriptor = described.get();
                CheckpointDecision decision = checkpoints.beforeExecute(pipelineContext, descriptor, input);
                if (decision.action() == CheckpointDecision.Action.RETURN_STORED) {
                    return Mono.just(buildToolResult(param, decision.storedOutput()));
                }
                if (decision.action() == CheckpointDecision.Action.REQUIRE_MANUAL) {
                    return Mono.just(buildToolResult(param, manualResult(decision.message())));
                }
            }

            ReActAgent subAgent = agentFactory.get();
            ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
            String parentToolCallId = toolUseBlock != null ? toolUseBlock.getId() : null;
            streamingHook.bindSubAgentCall(subAgent, parentToolCallId);

            String inputMessage = buildInputMessage(param);
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具被调用: name={}, parentToolCallId={}, input={}",
                    getName(), parentToolCallId, inputMessage);

            if (inputMessage == null || inputMessage.isBlank()) {
                return Mono.just(buildToolResult(param, JSONUtil.toJsonStr(Map.of(
                        "status", "error",
                        "message", "子 Agent 调用缺少 message 参数",
                        "toolName", getName()))));
            }

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(inputMessage)
                    .build();

            CheckpointDescriptor activeDescriptor = descriptor;
            return subAgent.call(userMsg)
                    .map(finalMsg -> {
                        cancellationToken.throwIfCancelled();
                        String result = finalMsg != null ? finalMsg.getTextContent() : "";
                        if (activeDescriptor != null) {
                            checkpoints.recordResult(pipelineContext, activeDescriptor, result);
                        }
                        return buildToolResult(param, result);
                    })
                    .doOnError(error -> {
                        if (activeDescriptor != null && !(error instanceof AgentCancelledException)) {
                            checkpoints.recordFailure(pipelineContext, activeDescriptor, error);
                        }
                    });
        }).onErrorResume(AgentCancelledException.class, e -> {
            log.info("[AgentScopeSubAgentToolAdapter] 子Agent工具执行被取消: name={}", getName());
            return Mono.error(e);
        }).onErrorResume(e -> {
            if (e instanceof AgentCancelledException) {
                return Mono.error(e);
            }
            log.error("[AgentScopeSubAgentToolAdapter] 子Agent工具执行失败: name={}", getName(), e);
            String errorResult = JSONUtil.toJsonStr(Map.of(
                    "status", "error",
                    "message", "子 Agent 执行失败: " + e.getMessage(),
                    "toolName", getName()));
            return Mono.just(buildToolResult(param, errorResult));
        });
    }

    private String manualResult(String message) {
        return JSONUtil.toJsonStr(Map.of(
                "status", "error",
                "message", message,
                "requiresManualResume", true,
                "toolName", getName()));
    }

    private String buildInputMessage(ToolCallParam param) {
        if (param == null || param.getInput() == null || param.getInput().isEmpty()) {
            return "";
        }
        Object message = param.getInput().get("message");
        if (message != null) {
            String messageText = String.valueOf(message);
            if (!messageText.isBlank()) {
                return messageText;
            }
        }
        return JSONUtil.toJsonStr(param.getInput());
    }

    private ToolResultBlock buildToolResult(ToolCallParam param, String result) {
        ToolResultBlock block = ToolResultBlock.text(result != null ? result : "");
        ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
        if (toolUseBlock == null) {
            return block;
        }
        return block.withIdAndName(toolUseBlock.getId(), toolUseBlock.getName());
    }
}
