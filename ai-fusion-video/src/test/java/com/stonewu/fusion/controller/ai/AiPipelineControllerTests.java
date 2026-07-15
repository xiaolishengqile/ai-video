package com.stonewu.fusion.controller.ai;

import com.stonewu.fusion.controller.ai.vo.PipelineStatusRespVO;
import com.stonewu.fusion.security.SecurityUserDetails;
import com.stonewu.fusion.service.ai.AgentConversationService;
import com.stonewu.fusion.service.ai.agentscope.AgentScopeAssistantService;
import com.stonewu.fusion.service.ai.agentscope.AgentScopePipelineRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiPipelineControllerTests {

    @Mock
    private AgentScopeAssistantService assistant;
    @Mock
    private AgentConversationService conversations;
    @Mock
    private AgentScopePipelineRuntime pipelineRuntime;

    private AiPipelineController controller;

    @BeforeEach
    void setUp() {
        SecurityUserDetails user = new SecurityUserDetails(7L, "tester", "", 1, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", user.getAuthorities()));
        controller = new AiPipelineController(assistant, conversations, pipelineRuntime);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runScopedEndpointsPassAuthenticatedOwnerToRuntime() {
        PipelineStatusRespVO status = new PipelineStatusRespVO().setRunId("run-1");
        when(pipelineRuntime.resume("run-1", 7L)).thenReturn(Flux.empty());
        when(pipelineRuntime.reconnect("run-1", 7L)).thenReturn(Flux.empty());
        when(pipelineRuntime.status("run-1", 7L)).thenReturn(status);

        controller.resume("run-1");
        controller.cancelRun("run-1");
        assertThat(controller.getRunStatus("run-1").getData()).isSameAs(status);
        controller.reconnectRun("run-1");

        verify(pipelineRuntime).resume("run-1", 7L);
        verify(pipelineRuntime).cancel("run-1", 7L);
        verify(pipelineRuntime).status("run-1", 7L);
        verify(pipelineRuntime).reconnect("run-1", 7L);
    }
}
