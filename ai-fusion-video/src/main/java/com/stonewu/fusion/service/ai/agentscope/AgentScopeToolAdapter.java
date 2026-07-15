package com.stonewu.fusion.service.ai.agentscope;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDecision;
import com.stonewu.fusion.service.ai.pipeline.CheckpointDescriptor;
import com.stonewu.fusion.service.ai.pipeline.PipelineExecutionContext;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointPolicyRegistry;
import com.stonewu.fusion.service.ai.pipeline.PipelineToolCheckpointService;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * AgentScope 工具适配器
 * <p>
 * 将现有 {@link ToolExecutor} 接口适配为 AgentScope 的 {@link AgentTool} 接口，
 * 使现有工具可以在 AgentScope ReActAgent 中使用。
 */
@Slf4j
public class AgentScopeToolAdapter implements AgentTool {

    private final ToolExecutor toolExecutor;
    private final ToolExecutionContext toolContext;
    private final AgentCancellationToken cancellationToken;
    private final PipelineExecutionContext pipelineContext;
    private final PipelineToolCheckpointPolicyRegistry checkpointPolicies;
    private final PipelineToolCheckpointService checkpoints;

    public AgentScopeToolAdapter(ToolExecutor toolExecutor, ToolExecutionContext toolContext,
            AgentCancellationToken cancellationToken) {
        this(toolExecutor, toolContext, cancellationToken, null, null, null);
    }

    public AgentScopeToolAdapter(
            ToolExecutor toolExecutor,
            ToolExecutionContext toolContext,
            AgentCancellationToken cancellationToken,
            PipelineExecutionContext pipelineContext,
            PipelineToolCheckpointPolicyRegistry checkpointPolicies,
            PipelineToolCheckpointService checkpoints) {
        this.toolExecutor = toolExecutor;
        this.toolContext = toolContext;
        this.cancellationToken = cancellationToken;
        this.pipelineContext = pipelineContext;
        this.checkpointPolicies = checkpointPolicies;
        this.checkpoints = checkpoints;
    }

    @Override
    public String getName() {
        return toolExecutor.getToolName();
    }

    @Override
    public String getDescription() {
        String desc = toolExecutor.getToolDescription();
        return desc != null ? desc : toolExecutor.getDisplayName();
    }

    @Override
    public Map<String, Object> getParameters() {
        String schema = toolExecutor.getParametersSchema();
        if (schema == null || schema.isBlank()) {
            return Map.of(
                    "type", "object",
                    "properties", Map.of());
        }
        try {
            return JSONUtil.parseObj(schema);
        } catch (Exception e) {
            log.warn("工具参数 Schema 解析失败: tool={}, schema={}", getName(), schema, e);
            return Map.of(
                    "type", "object",
                    "properties", Map.of());
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            // 工具执行前检查取消标志，避免已取消后仍执行耗时工具
            cancellationToken.throwIfCancelled();

            String input = JSONUtil.toJsonStr(param.getInput());
            log.info("[AgentScopeToolAdapter] 工具被调用: name={}, input={}", getName(), input);

            CheckpointDescriptor descriptor = null;
            if (pipelineContext != null) {
                Optional<CheckpointDescriptor> described = checkpointPolicies.describe(getName(), input);
                if (described.isEmpty()) {
                    if (!checkpointPolicies.isReadOnly(getName())) {
                        return buildToolResult(param, manualResult("工具未配置检查点策略，已阻止自动执行"));
                    }
                } else {
                    descriptor = described.get();
                    CheckpointDecision decision = checkpoints.beforeExecute(pipelineContext, descriptor, input);
                    if (decision.action() == CheckpointDecision.Action.RETURN_STORED) {
                        return buildToolResult(param, decision.storedOutput());
                    }
                    if (decision.action() == CheckpointDecision.Action.REQUIRE_MANUAL) {
                        return buildToolResult(param, manualResult(decision.message()));
                    }
                }
            }

            String result;
            try {
                result = toolExecutor.execute(input, toolContext);
            } catch (RuntimeException error) {
                if (descriptor != null && !(error instanceof AgentCancelledException)) {
                    checkpoints.recordFailure(pipelineContext, descriptor, error);
                }
                throw error;
            }

            // 工具执行后再次检查，避免结果被提交回已取消的流
            cancellationToken.throwIfCancelled();

            if (descriptor != null) {
                checkpoints.recordResult(pipelineContext, descriptor, result);
            }

            return buildToolResult(param, result);
        }).onErrorResume(AgentCancelledException.class, e -> {
            log.info("[AgentScopeToolAdapter] 工具执行被取消: name={}", getName());
            return Mono.error(e);
        }).onErrorResume(e -> {
            if (e instanceof AgentCancelledException) {
                return Mono.error(e);
            }
            log.error("[AgentScopeToolAdapter] 工具执行失败: name={}", getName(), e);
            String errorResult = JSONUtil.toJsonStr(Map.of(
                    "status", "error",
                    "message", "工具执行失败: " + e.getMessage(),
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

    private ToolResultBlock buildToolResult(ToolCallParam param, String result) {
        ToolResultBlock block = ToolResultBlock.text(result);
        ToolUseBlock toolUseBlock = param != null ? param.getToolUseBlock() : null;
        if (toolUseBlock == null) {
            return block;
        }
        return block.withIdAndName(toolUseBlock.getId(), toolUseBlock.getName());
    }
}
