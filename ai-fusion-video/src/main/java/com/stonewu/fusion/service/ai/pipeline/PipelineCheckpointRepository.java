package com.stonewu.fusion.service.ai.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.mapper.ai.PipelineCheckpointMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PipelineCheckpointRepository {

    private final PipelineCheckpointMapper mapper;
    private final PipelineJsonSnapshot snapshots;

    public PipelineCheckpointRepository(PipelineCheckpointMapper mapper, PipelineJsonSnapshot snapshots) {
        this.mapper = mapper;
        this.snapshots = snapshots;
    }

    public PipelineCheckpoint upsertRunning(
            Long pipelineRunId,
            CheckpointDescriptor descriptor,
            String inputJson) {
        mapper.upsertRunning(
                pipelineRunId,
                descriptor.checkpointKey(),
                descriptor.toolName(),
                descriptor.scopeType(),
                descriptor.scopeId(),
                descriptor.replayPolicy().name(),
                snapshots.trim(inputJson));
        PipelineCheckpoint checkpoint = find(pipelineRunId, descriptor.checkpointKey());
        if (checkpoint == null) {
            throw new IllegalStateException("检查点写入后无法读取: " + descriptor.checkpointKey());
        }
        return checkpoint;
    }

    public void markSucceeded(Long pipelineRunId, String checkpointKey, String outputJson) {
        mapper.update(null, baseUpdate(pipelineRunId, checkpointKey)
                .set(PipelineCheckpoint::getStatus, PipelineCheckpointStatus.SUCCEEDED)
                .set(PipelineCheckpoint::getOutputJson, snapshots.trim(outputJson))
                .set(PipelineCheckpoint::getErrorCategory, null)
                .set(PipelineCheckpoint::getErrorCode, null)
                .set(PipelineCheckpoint::getErrorMessage, null));
    }

    public void markFailed(Long pipelineRunId, String checkpointKey, PipelineFailure failure) {
        mapper.update(null, baseUpdate(pipelineRunId, checkpointKey)
                .set(PipelineCheckpoint::getStatus, PipelineCheckpointStatus.FAILED)
                .set(PipelineCheckpoint::getErrorCategory, failure.category().name())
                .set(PipelineCheckpoint::getErrorCode, failure.code())
                .set(PipelineCheckpoint::getErrorMessage, failure.message()));
    }

    public void markUnknown(Long pipelineRunId, String checkpointKey) {
        mapper.update(null, baseUpdate(pipelineRunId, checkpointKey)
                .set(PipelineCheckpoint::getStatus, PipelineCheckpointStatus.UNKNOWN));
    }

    public void markRunningUnknown(Long pipelineRunId) {
        mapper.update(null, new LambdaUpdateWrapper<PipelineCheckpoint>()
                .eq(PipelineCheckpoint::getPipelineRunId, pipelineRunId)
                .eq(PipelineCheckpoint::getStatus, PipelineCheckpointStatus.RUNNING)
                .set(PipelineCheckpoint::getStatus, PipelineCheckpointStatus.UNKNOWN));
    }

    public PipelineCheckpoint find(Long pipelineRunId, String checkpointKey) {
        return mapper.selectOne(new LambdaQueryWrapper<PipelineCheckpoint>()
                .eq(PipelineCheckpoint::getPipelineRunId, pipelineRunId)
                .eq(PipelineCheckpoint::getCheckpointKey, checkpointKey));
    }

    public List<PipelineCheckpoint> listByRunId(Long pipelineRunId) {
        return mapper.selectList(new LambdaQueryWrapper<PipelineCheckpoint>()
                .eq(PipelineCheckpoint::getPipelineRunId, pipelineRunId)
                .orderByAsc(PipelineCheckpoint::getId));
    }

    private LambdaUpdateWrapper<PipelineCheckpoint> baseUpdate(Long pipelineRunId, String checkpointKey) {
        return new LambdaUpdateWrapper<PipelineCheckpoint>()
                .eq(PipelineCheckpoint::getPipelineRunId, pipelineRunId)
                .eq(PipelineCheckpoint::getCheckpointKey, checkpointKey);
    }
}
