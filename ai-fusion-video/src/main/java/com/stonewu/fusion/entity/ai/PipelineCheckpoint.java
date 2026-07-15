package com.stonewu.fusion.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.fusion.common.BaseEntity;
import com.stonewu.fusion.service.ai.pipeline.CheckpointReplayPolicy;
import com.stonewu.fusion.service.ai.pipeline.PipelineCheckpointStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@TableName("afv_ai_pipeline_checkpoint")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineCheckpoint extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pipelineRunId;
    private String checkpointKey;
    private String toolName;
    private String scopeType;
    private String scopeId;
    private CheckpointReplayPolicy replayPolicy;
    private PipelineCheckpointStatus status;
    private String inputJson;
    private String outputJson;
    @Builder.Default
    private Integer attemptCount = 1;
    private String errorCategory;
    private String errorCode;
    private String errorMessage;
}
