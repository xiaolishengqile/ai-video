package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.entity.ai.AgentConversation;
import com.stonewu.fusion.entity.ai.AgentMessage;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.AgentMessageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyPipelineCheckpointImporterTests {

    @Test
    void pairsSuccessfulToolHistoryAndMarksOrphanRunningUnknown() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentMessageService messages = mock(AgentMessageService.class);
        PipelineRunRepository runs = mock(PipelineRunRepository.class);
        PipelineCheckpointRepository checkpoints = mock(PipelineCheckpointRepository.class);
        AgentConversation conversation = AgentConversation.builder()
                .id(5L)
                .conversationId("legacy-conversation")
                .userId(7L)
                .projectId(9L)
                .contextType("script")
                .contextId(40L)
                .agentType("script_full_parse")
                .title("解析剧本")
                .build();
        PipelineRun run = PipelineRun.builder().id(11L).runId("run-1").build();
        when(conversations.getByConversationId("legacy-conversation")).thenReturn(conversation);
        when(runs.create(any(), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(run);
        when(messages.listByConversation("legacy-conversation")).thenReturn(List.of(
                tool("call-1", "save_script_episode", "running",
                        "{\"scriptId\":40,\"episodeNumber\":12}"),
                tool("call-1", "save_script_episode", "success",
                        "{\"scriptEpisodeId\":112,\"status\":\"success\"}"),
                tool("call-2", "run_script_asset_prebinding", "running",
                        "{\"projectId\":9,\"scriptId\":40,\"scriptEpisodeId\":112}")));
        LegacyPipelineCheckpointImporter importer = new LegacyPipelineCheckpointImporter(
                conversations,
                messages,
                runs,
                checkpoints,
                new PipelineToolCheckpointPolicyRegistry());

        PipelineRun result = importer.importConversation("legacy-conversation", 7L);

        assertThat(result).isSameAs(run);
        verify(checkpoints).markSucceeded(
                11L, "save_script_episode:40:12",
                "{\"scriptEpisodeId\":112,\"status\":\"success\"}");
        verify(checkpoints).markUnknown(11L, "save_script_episode:40:12");
        verify(checkpoints).markUnknown(11L, "run_script_asset_prebinding:112");
        verify(conversations).linkPipelineRun(conversation, run);
    }

    @Test
    void importingAlreadyLinkedConversationIsIdempotent() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentMessageService messages = mock(AgentMessageService.class);
        PipelineRunRepository runs = mock(PipelineRunRepository.class);
        PipelineCheckpointRepository checkpoints = mock(PipelineCheckpointRepository.class);
        AgentConversation conversation = AgentConversation.builder()
                .conversationId("legacy-conversation")
                .pipelineRunId(11L)
                .build();
        PipelineRun existing = PipelineRun.builder().id(11L).runId("run-1").build();
        when(conversations.getByConversationId("legacy-conversation")).thenReturn(conversation);
        when(runs.requireById(11L)).thenReturn(existing);
        LegacyPipelineCheckpointImporter importer = new LegacyPipelineCheckpointImporter(
                conversations,
                messages,
                runs,
                checkpoints,
                new PipelineToolCheckpointPolicyRegistry());

        PipelineRun result = importer.importConversation("legacy-conversation", 7L);

        assertThat(result).isSameAs(existing);
        verify(messages, never()).listByConversation(any());
        verify(checkpoints, never()).upsertRunning(any(), any(), any());
    }

    private AgentMessage tool(String callId, String tool, String status, String content) {
        return AgentMessage.builder()
                .role("tool")
                .toolCallId(callId)
                .toolName(tool)
                .toolStatus(status)
                .content(content)
                .build();
    }
}
