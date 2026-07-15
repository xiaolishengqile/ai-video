package com.stonewu.fusion.controller.ai.vo;

import com.stonewu.fusion.service.ai.pipeline.PipelineResumeType;
import com.stonewu.fusion.service.ai.pipeline.PipelineRunStatus;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PipelineStatusRespVO {

    private String runId;
    private PipelineRunStatus status;
    private String activeConversationId;
    private Integer attemptNumber;
    private PipelineResumeType resumeType;
    private Integer autoResumeCount;
    private Integer maxAutoResume;
    private String errorCategory;
    private String errorCode;
    private String errorMessage;
    private Boolean canResume;
}
