package com.stonewu.fusion.service.ai.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.mapper.ai.PipelineRunMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class PipelineRunRepository {

    private final PipelineRunMapper mapper;
    private final PipelineJsonSnapshot snapshots;

    public PipelineRunRepository(PipelineRunMapper mapper, PipelineJsonSnapshot snapshots) {
        this.mapper = mapper;
        this.snapshots = snapshots;
    }

    public PipelineRun create(AiChatReqVO request, Long userId) {
        PipelineRun run = PipelineRun.builder()
                .runId(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .projectId(request.getProjectId())
                .agentType(request.getAgentType())
                .title(resolveTitle(request))
                .requestJson(snapshots.serialize(request))
                .status(PipelineRunStatus.RUNNING)
                .build();
        mapper.insert(run);
        return run;
    }

    public PipelineRun requireByRunId(String runId) {
        PipelineRun run = mapper.selectOne(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getRunId, runId));
        if (run == null) {
            throw new BusinessException(404, "Pipeline 任务不存在");
        }
        return run;
    }

    public void update(PipelineRun run) {
        mapper.updateById(run);
    }

    private String resolveTitle(AiChatReqVO request) {
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            return request.getTitle();
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return request.getAgentType();
        }
        return message.length() <= 50 ? message : message.substring(0, 50);
    }
}
