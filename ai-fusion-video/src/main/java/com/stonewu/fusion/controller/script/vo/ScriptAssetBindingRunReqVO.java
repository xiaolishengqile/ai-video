package com.stonewu.fusion.controller.script.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptAssetBindingRunReqVO {
    @NotNull
    private Long projectId;
    @NotNull
    private Long scriptId;
    @NotNull
    private Long scriptEpisodeId;
}
