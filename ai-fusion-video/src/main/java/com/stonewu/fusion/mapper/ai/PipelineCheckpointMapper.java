package com.stonewu.fusion.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PipelineCheckpointMapper extends BaseMapper<PipelineCheckpoint> {

    @Insert("""
            INSERT INTO afv_ai_pipeline_checkpoint (
                pipeline_run_id, checkpoint_key, tool_name, scope_type, scope_id,
                replay_policy, status, input_json, attempt_count, deleted,
                create_time, update_time
            ) VALUES (
                #{pipelineRunId}, #{checkpointKey}, #{toolName}, #{scopeType}, #{scopeId},
                #{replayPolicy}, 'RUNNING', #{inputJson}, 1, 0,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON DUPLICATE KEY UPDATE
                tool_name = VALUES(tool_name),
                scope_type = VALUES(scope_type),
                scope_id = VALUES(scope_id),
                replay_policy = VALUES(replay_policy),
                status = 'RUNNING',
                input_json = VALUES(input_json),
                attempt_count = attempt_count + 1,
                error_category = NULL,
                error_code = NULL,
                error_message = NULL,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertRunning(
            @Param("pipelineRunId") Long pipelineRunId,
            @Param("checkpointKey") String checkpointKey,
            @Param("toolName") String toolName,
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("replayPolicy") String replayPolicy,
            @Param("inputJson") String inputJson);
}
