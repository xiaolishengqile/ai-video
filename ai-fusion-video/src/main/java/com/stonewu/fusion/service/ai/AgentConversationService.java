package com.stonewu.fusion.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.AgentConversation;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.mapper.ai.AgentConversationMapper;
import com.stonewu.fusion.service.ai.pipeline.PipelineAttempt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 对话索引服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentConversationService {

    private final AgentConversationMapper conversationMapper;

    @Transactional
    public AgentConversation createPipelineAttempt(PipelineRun run, PipelineAttempt attempt) {
        AiChatReqVO request = attempt.request();
        AgentConversation conversation = AgentConversation.builder()
                .conversationId(attempt.conversationId())
                .pipelineRunId(run.getId())
                .attemptNumber(attempt.attemptNumber())
                .resumeType(attempt.resumeType())
                .userId(run.getUserId())
                .projectId(run.getProjectId())
                .agentType(run.getAgentType())
                .category(request.getCategory())
                .title(run.getTitle())
                .status("running")
                .messageCount(0)
                .lastMessageTime(LocalDateTime.now())
                .build();
        conversationMapper.insert(conversation);
        return conversation;
    }

    public int nextAttemptNumber(Long pipelineRunId) {
        AgentConversation latest = getLatestPipelineAttempt(pipelineRunId);
        return latest == null ? 0 : latest.getAttemptNumber() + 1;
    }

    public AgentConversation getLatestPipelineAttempt(Long pipelineRunId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getPipelineRunId, pipelineRunId)
                        .orderByDesc(AgentConversation::getAttemptNumber)
                        .last("LIMIT 1"));
    }

    @Transactional
    public void linkPipelineRun(AgentConversation conversation, PipelineRun run) {
        conversation.setPipelineRunId(run.getId());
        conversation.setAttemptNumber(0);
        conversation.setResumeType(com.stonewu.fusion.service.ai.pipeline.PipelineResumeType.INITIAL);
        conversationMapper.updateById(conversation);
    }

    @Transactional
    public AgentConversation createOrUpdate(String conversationId, Long userId, Long projectId,
                                            String contextType, Long contextId,
                                            String agentType, String title, String category) {
        AgentConversation existing = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>().eq(AgentConversation::getConversationId, conversationId));
        if (existing != null) {
            if (title != null) existing.setTitle(title);
            existing.setLastMessageTime(LocalDateTime.now());
            existing.setMessageCount(existing.getMessageCount() + 1);
            conversationMapper.updateById(existing);
            return existing;
        }

        AgentConversation conv = AgentConversation.builder()
                .conversationId(conversationId)
                .userId(userId)
                .projectId(projectId)
                .contextType(contextType)
                .contextId(contextId)
                .agentType(agentType)
                .category(category)
                .title(title != null ? title : "新对话")
                .status("running")
                .messageCount(1)
                .lastMessageTime(LocalDateTime.now())
                .build();
        conversationMapper.insert(conv);
        return conv;
    }

    @Transactional
    public void finish(String conversationId, String status) {
        AgentConversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>().eq(AgentConversation::getConversationId, conversationId));
        if (conv != null) {
            conv.setStatus(status);
            conv.setLastMessageTime(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }
    }

    public AgentConversation getByConversationId(String conversationId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>().eq(AgentConversation::getConversationId, conversationId));
    }

    public PageResult<AgentConversation> listByUser(Long userId, int pageNo, int pageSize) {
        return PageResult.of(conversationMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .orderByDesc(AgentConversation::getUpdateTime)));
    }

    public PageResult<AgentConversation> listByUserAndCategory(Long userId, String category, int pageNo, int pageSize) {
        return PageResult.of(conversationMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getCategory, category)
                        .orderByDesc(AgentConversation::getUpdateTime)));
    }

    public List<AgentConversation> listByProject(Long projectId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getProjectId, projectId)
                .orderByDesc(AgentConversation::getUpdateTime));
    }

    /**
     * 查询用户正在运行的 pipeline 对话（status=running）
     */
    public List<AgentConversation> listRunning(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, "running")
                .orderByDesc(AgentConversation::getUpdateTime));
    }

    public void delete(Long id) {
        conversationMapper.deleteById(id);
    }
}
