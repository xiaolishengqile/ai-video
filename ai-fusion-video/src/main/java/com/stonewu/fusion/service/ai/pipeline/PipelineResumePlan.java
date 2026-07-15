package com.stonewu.fusion.service.ai.pipeline;

import java.util.List;

public record PipelineResumePlan(
        List<String> completed,
        List<String> pending,
        List<String> constraints) {

    public PipelineResumePlan {
        completed = completed == null ? List.of() : List.copyOf(completed);
        pending = pending == null ? List.of() : List.copyOf(pending);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public String toPromptBlock() {
        return """
                <resume_context>
                  <completed>%s</completed>
                  <pending>%s</pending>
                  <constraints>%s</constraints>
                </resume_context>
                """.formatted(join(completed), join(pending), join(constraints)).trim();
    }

    private String join(List<String> values) {
        return escape(String.join("；", values));
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
