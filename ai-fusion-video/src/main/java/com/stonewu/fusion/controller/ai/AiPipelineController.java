package com.stonewu.fusion.controller.ai;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.controller.ai.vo.AiChatStreamRespVO;
import com.stonewu.fusion.controller.ai.vo.PipelineStatusRespVO;
import com.stonewu.fusion.entity.ai.AgentConversation;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.agentscope.AgentScopeAssistantService;
import com.stonewu.fusion.service.ai.agentscope.AgentScopePipelineRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * AI Pipeline Controller（单次自动执行的工作流）
 * <p>
 * 与 AiAssistantController（多轮对话）分离，
 * 便于后续独立调整 pipeline 的参数、限流、鉴权等逻辑。
 */
@Tag(name = "AI Pipeline")
@RestController
@RequestMapping("/api/ai/pipeline")
@RequiredArgsConstructor
public class AiPipelineController {

    private final AgentScopeAssistantService aiAssistantService;
    private final AgentConversationService conversationService;
    private final AgentScopePipelineRuntime pipelineRuntime;

    @Operation(summary = "启动 Pipeline（SSE 流式）")
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> run(@RequestBody AiChatReqVO reqVO) {
        Long userId = requireCurrentUserId();
        return pipelineRuntime.run(reqVO, userId);
    }

    @Operation(summary = "人工继续 Pipeline")
    @PostMapping(value = "/{runId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> resume(@PathVariable String runId) {
        return pipelineRuntime.resume(runId, requireCurrentUserId());
    }

    @Operation(summary = "按逻辑任务取消 Pipeline")
    @PostMapping("/{runId}/cancel")
    public CommonResult<Boolean> cancelRun(@PathVariable String runId) {
        pipelineRuntime.cancel(runId, requireCurrentUserId());
        return CommonResult.success(true);
    }

    @Operation(summary = "查询逻辑 Pipeline 状态")
    @GetMapping("/{runId}/status")
    public CommonResult<PipelineStatusRespVO> getRunStatus(@PathVariable String runId) {
        return CommonResult.success(pipelineRuntime.status(runId, requireCurrentUserId()));
    }

    @Operation(summary = "按逻辑任务重连 Pipeline")
    @GetMapping(value = "/{runId}/reconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> reconnectRun(@PathVariable String runId) {
        return pipelineRuntime.reconnect(runId, requireCurrentUserId());
    }

    @Operation(summary = "取消 Pipeline")
    @PostMapping("/cancel")
    public CommonResult<Boolean> cancel(@RequestParam String conversationId) {
        aiAssistantService.cancelStream(conversationId);
        return CommonResult.success(true);
    }

    @Operation(summary = "重连 Pipeline（页面刷新后恢复 SSE）")
    @GetMapping(value = "/reconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatStreamRespVO> reconnect(@RequestParam String conversationId) {
        return aiAssistantService.reconnectStream(conversationId);
    }

    @Operation(summary = "查询 Pipeline 流状态")
    @GetMapping("/status")
    public CommonResult<String> getStatus(@RequestParam String conversationId) {
        return CommonResult.success(aiAssistantService.getStreamStatus(conversationId));
    }

    @Operation(summary = "查询运行中的 Pipeline 列表")
    @GetMapping("/running")
    public CommonResult<List<AgentConversation>> listRunning() {
        Long userId = requireCurrentUserId();
        return CommonResult.success(conversationService.listRunning(userId));
    }
}
