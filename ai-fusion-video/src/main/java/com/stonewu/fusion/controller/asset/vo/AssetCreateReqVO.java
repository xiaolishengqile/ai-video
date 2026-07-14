package com.stonewu.fusion.controller.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建资产请求 VO
 */
@Schema(description = "创建资产请求")
@Data
public class AssetCreateReqVO {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "所属集不能为空")
    @Min(value = 1, message = "所属集必须大于等于 1")
    @Max(value = 99, message = "所属集不能超过 99")
    private Integer episodeNumber;

    @NotBlank(message = "资产类型不能为空")
    private String type;

    @NotBlank(message = "资产名称不能为空")
    private String name;

    private String description;
    private String coverUrl;
    private String properties;
    private String tags;
    private String aiPrompt;
}
