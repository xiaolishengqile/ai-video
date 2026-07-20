package com.stonewu.fusion.service.ai.pipeline;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.mapper.ai.PipelineCheckpointMapper;
import com.stonewu.fusion.mapper.ai.PipelineRunMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelinePersistenceTests {

    @Test
    void activeConversationIdCanBeClearedByUpdateById() throws Exception {
        TableField mapping = PipelineRun.class
                .getDeclaredField("activeConversationId")
                .getAnnotation(TableField.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
    }

    @Test
    void mapsEntitiesAndMigrationToStableTablesAndUniqueKeys() throws Exception {
        assertThat(PipelineRun.class.getAnnotation(TableName.class).value())
                .isEqualTo("afv_ai_pipeline_run");
        assertThat(PipelineCheckpoint.class.getAnnotation(TableName.class).value())
                .isEqualTo("afv_ai_pipeline_checkpoint");

        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/V1.0.6.2.5__ai_pipeline_checkpoint_resume.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration)
                    .contains("UNIQUE KEY `uk_pipeline_run_id` (`run_id`)")
                    .contains("UNIQUE KEY `uk_pipeline_checkpoint` (`pipeline_run_id`, `checkpoint_key`)")
                    .contains("ADD COLUMN `pipeline_run_id`")
                    .contains("ADD COLUMN `attempt_number`")
                    .contains("ADD COLUMN `resume_type`");
        }
    }

    @Test
    void createsPipelineRunFromOriginalRequest() {
        PipelineRunMapper mapper = mock(PipelineRunMapper.class);
        PipelineRunRepository repository = new PipelineRunRepository(
                mapper,
                new PipelineJsonSnapshot(new ObjectMapper()));
        AiChatReqVO request = new AiChatReqVO()
                .setAgentType("script_parser")
                .setProjectId(40L)
                .setTitle("解析剧本")
                .setMessage("请解析完整剧本")
                .setContext(Map.of("scriptId", 40));

        PipelineRun result = repository.create(request, 7L);

        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(mapper).insert(captor.capture());
        PipelineRun inserted = captor.getValue();
        assertThat(result).isSameAs(inserted);
        assertThat(inserted.getRunId()).isNotBlank();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getProjectId()).isEqualTo(40L);
        assertThat(inserted.getStatus()).isEqualTo(PipelineRunStatus.RUNNING);
        assertThat(inserted.getAutoResumeCount()).isZero();
        assertThat(inserted.getMaxAutoResume()).isEqualTo(2);
        assertThat(inserted.getRequestJson()).contains("script_parser", "scriptId");
    }

    @Test
    void upsertsOneCheckpointAndIncrementsAttemptCountAtomically() throws Exception {
        PipelineCheckpointMapper mapper = mock(PipelineCheckpointMapper.class);
        PipelineCheckpoint stored = PipelineCheckpoint.builder()
                .id(9L)
                .pipelineRunId(3L)
                .checkpointKey("save_script_episode:40:12")
                .status(PipelineCheckpointStatus.RUNNING)
                .attemptCount(2)
                .build();
        when(mapper.selectOne(any())).thenReturn(stored);
        PipelineCheckpointRepository repository = new PipelineCheckpointRepository(
                mapper,
                new PipelineJsonSnapshot(new ObjectMapper()));
        CheckpointDescriptor descriptor = new CheckpointDescriptor(
                "save_script_episode:40:12",
                "save_script_episode",
                "episode",
                "40:12",
                CheckpointReplayPolicy.SAFE_REPLAY);

        repository.upsertRunning(3L, descriptor, "{\"scriptId\":40,\"episodeNumber\":12}");
        PipelineCheckpoint result = repository.upsertRunning(
                3L,
                descriptor,
                "{\"scriptId\":40,\"episodeNumber\":12}");

        verify(mapper, times(2)).upsertRunning(
                3L,
                descriptor.checkpointKey(),
                descriptor.toolName(),
                descriptor.scopeType(),
                descriptor.scopeId(),
                descriptor.replayPolicy().name(),
                "{\"scriptId\":40,\"episodeNumber\":12}");
        assertThat(result.getId()).isEqualTo(9L);
        assertThat(result.getAttemptCount()).isEqualTo(2);

        Insert annotation = PipelineCheckpointMapper.class
                .getMethod("upsertRunning", Long.class, String.class, String.class,
                        String.class, String.class, String.class, String.class)
                .getAnnotation(Insert.class);
        assertThat(String.join(" ", annotation.value()))
                .containsIgnoringCase("ON DUPLICATE KEY UPDATE")
                .contains("attempt_count = attempt_count + 1");
    }

    @Test
    void trimsOversizedSnapshotsWithoutProducingInvalidJson() throws Exception {
        PipelineJsonSnapshot snapshot = new PipelineJsonSnapshot(new ObjectMapper());

        String result = snapshot.trim("{\"payload\":\"" + "x".repeat(70_000) + "\"}");

        assertThat(result.length()).isLessThanOrEqualTo(PipelineJsonSnapshot.MAX_LENGTH);
        assertThat(new ObjectMapper().readTree(result).path("truncated").asBoolean()).isTrue();
    }

    @Test
    void sanitizesCredentialsInJsonSnapshots() {
        PipelineJsonSnapshot snapshot = new PipelineJsonSnapshot(new ObjectMapper());

        String result = snapshot.trim(
                "{\"Authorization\":\"Bearer sk-secret\",\"apiKey\":\"top-secret\"}");

        assertThat(result)
                .doesNotContain("sk-secret", "top-secret")
                .contains("[REDACTED]");
    }

    @Test
    void rejectsOptimisticLockConflictWhenUpdatingRun() {
        PipelineRunMapper mapper = mock(PipelineRunMapper.class);
        when(mapper.updateById(any(PipelineRun.class))).thenReturn(0);
        PipelineRunRepository repository = new PipelineRunRepository(
                mapper,
                new PipelineJsonSnapshot(new ObjectMapper()));

        assertThatThrownBy(() -> repository.update(PipelineRun.builder().id(1L).version(2).build()))
                .isInstanceOf(com.stonewu.fusion.common.BusinessException.class)
                .hasMessageContaining("状态已变化");
    }
}
