package com.stonewu.fusion.entity.ai;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.stonewu.fusion.common.BaseEntity;
import com.stonewu.fusion.service.ai.pipeline.PipelineRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@TableName("afv_ai_pipeline_run")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRun extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Long userId;
    private Long projectId;
    private String agentType;
    private String title;
    private String requestJson;
    private PipelineRunStatus status;
    @Builder.Default
    private Integer autoResumeCount = 0;
    @Builder.Default
    private Integer maxAutoResume = 1;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String activeConversationId;
    private String lastErrorCategory;
    private String lastErrorCode;
    private String lastErrorMessage;
    @Version
    @Builder.Default
    private Integer version = 0;
}
