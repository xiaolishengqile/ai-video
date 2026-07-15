package com.stonewu.fusion.controller.ai.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * AI 助手流式响应 VO（SSE 事件数据）
 */
@Data
@Accessors(chain = true)
public class AiChatStreamRespVO {

    /** 消息 ID */
    private String messageId;

    /** 会话 ID */
    private String conversationId;

    /** Pipeline 逻辑任务 ID；普通对话为空 */
    private String pipelineRunId;

    /** 当前执行尝试序号 */
    private Integer attemptNumber;

    /** 当前执行类型：INITIAL / AUTO / MANUAL */
    private String resumeType;

    /** 输出类型：REASONING / CONTENT / TOOL_CALL / TOOL_FINISHED / SUB_AGENT_FINISHED / DONE / ERROR / CANCELLED */
    private String outputType;

    /** 文本内容（增量） */
    private String content;

    /** 思考内容（增量） */
    private String reasoningContent;

    /** 思考开始时间 */
    private Long reasoningStartTime;

    /** 思考耗时（毫秒，首个 CONTENT 事件携带） */
    private Long reasoningDurationMs;

    /** 工具调用信息 */
    private List<ToolCallVO> toolCalls;

    /** 工具调用 ID */
    private String toolCallId;

    /** 工具名 */
    private String toolName;

    /** 工具执行结果 */
    private String toolResult;

    /** 工具执行状态：success / error */
    private String toolStatus;

    /** 父级工具调用 ID（子 Agent 输出时用于归属映射） */
    private String parentToolCallId;

    /** 产生此事件的 Agent 名称 */
    private String agentName;

    /** 是否结束 */
    private Boolean finished;

    /** 错误信息 */
    private String error;

    @Data
    @Accessors(chain = true)
    public static class ToolCallVO {
        private String id;
        private String name;
        private String arguments;
    }
}
