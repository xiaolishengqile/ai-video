package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.AgentConversation;
import com.stonewu.fusion.entity.ai.AgentMessage;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.AgentMessageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LegacyPipelineCheckpointImporter {

    private final AgentConversationService conversations;
    private final AgentMessageService messages;
    private final PipelineRunRepository runs;
    private final PipelineCheckpointRepository checkpoints;
    private final PipelineToolCheckpointPolicyRegistry policies;

    public LegacyPipelineCheckpointImporter(
            AgentConversationService conversations,
            AgentMessageService messages,
            PipelineRunRepository runs,
            PipelineCheckpointRepository checkpoints,
            PipelineToolCheckpointPolicyRegistry policies) {
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.policies = policies;
    }

    @Transactional
    public PipelineRun importConversation(String conversationId, Long userId) {
        AgentConversation conversation = conversations.getByConversationId(conversationId);
        if (conversation == null) {
            throw new BusinessException(404, "历史会话不存在");
        }
        if (conversation.getPipelineRunId() != null) {
            return runs.requireById(conversation.getPipelineRunId());
        }
        if (conversation.getUserId() != null && !conversation.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权恢复该历史会话");
        }

        List<AgentMessage> history = messages.listByConversation(conversationId);
        PipelineRun run = runs.create(buildRequest(conversation, history), userId);
        run.setStatus(PipelineRunStatus.WAITING_MANUAL_RESUME);
        run.setActiveConversationId(null);
        runs.update(run);
        importToolHistory(run.getId(), history);
        conversations.linkPipelineRun(conversation, run);
        return run;
    }

    private void importToolHistory(Long pipelineRunId, List<AgentMessage> messages) {
        Map<String, ToolHistory> calls = new LinkedHashMap<>();
        for (AgentMessage message : messages) {
            if (!"tool".equals(message.getRole()) || message.getToolCallId() == null) {
                continue;
            }
            ToolHistory history = calls.computeIfAbsent(message.getToolCallId(), ignored -> new ToolHistory());
            if ("running".equalsIgnoreCase(message.getToolStatus())) {
                history.running = message;
            } else {
                history.terminal = message;
            }
        }

        for (ToolHistory history : calls.values()) {
            if (history.running == null) {
                continue;
            }
            Optional<CheckpointDescriptor> descriptor = policies.describe(
                    history.running.getToolName(), history.running.getContent());
            if (descriptor.isEmpty()) {
                continue;
            }
            CheckpointDescriptor checkpoint = descriptor.get();
            checkpoints.upsertRunning(pipelineRunId, checkpoint, history.running.getContent());
            if (history.terminal == null) {
                checkpoints.markUnknown(pipelineRunId, checkpoint.checkpointKey());
            } else if ("success".equalsIgnoreCase(history.terminal.getToolStatus())) {
                checkpoints.markSucceeded(
                        pipelineRunId, checkpoint.checkpointKey(), history.terminal.getContent());
                // 历史消息不是最终事实；先保留输出，再由恢复策略结合当前业务数据确认。
                checkpoints.markUnknown(pipelineRunId, checkpoint.checkpointKey());
            } else {
                checkpoints.markFailed(
                        pipelineRunId,
                        checkpoint.checkpointKey(),
                        new PipelineFailure(
                                PipelineFailureCategory.BUSINESS_ERROR,
                                null,
                                history.terminal.getContent(),
                                false));
            }
        }
    }

    private AiChatReqVO buildRequest(AgentConversation conversation, List<AgentMessage> messages) {
        String userMessage = messages.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(AgentMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse("继续执行历史 Pipeline 任务");
        Map<String, Object> context = new LinkedHashMap<>();
        if (conversation.getContextId() != null) {
            String key = "script".equals(conversation.getContextType())
                    ? "scriptId"
                    : conversation.getContextType() + "Id";
            context.put(key, conversation.getContextId());
        }
        return new AiChatReqVO()
                .setMessage(userMessage)
                .setAgentType(conversation.getAgentType())
                .setCategory(conversation.getCategory())
                .setTitle(conversation.getTitle())
                .setProjectId(conversation.getProjectId())
                .setContext(context);
    }

    private static final class ToolHistory {
        private AgentMessage running;
        private AgentMessage terminal;
    }
}
