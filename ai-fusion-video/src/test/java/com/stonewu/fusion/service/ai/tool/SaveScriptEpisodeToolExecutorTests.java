package com.stonewu.fusion.service.ai.tool;

import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.script.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SaveScriptEpisodeToolExecutorTests {

    @Mock
    private ScriptService scriptService;

    @InjectMocks
    private SaveScriptEpisodeToolExecutor executor;

    @Test
    void saveReturnsRecoverableChineseErrorWhenRequiredFieldsAreMissing() {
        String result = executor.execute("{}", ToolExecutionContext.builder().userId(9L).build());

        assertThat(result)
                .contains("\"status\":\"error\"")
                .contains("保存剧本分集缺少必要参数: scriptId")
                .contains("save_script_episode");
        verify(scriptService, never()).saveEpisode(anyLong(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void schemaLetsExecutorReturnChineseRequiredFieldErrors() {
        assertThat(executor.getParametersSchema()).contains("\"required\": []");
    }
}
