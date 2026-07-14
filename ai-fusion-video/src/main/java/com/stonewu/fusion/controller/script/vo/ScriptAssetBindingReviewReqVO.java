package com.stonewu.fusion.controller.script.vo;

import lombok.Data;

@Data
public class ScriptAssetBindingReviewReqVO {
    private Long assetId;
    private Long assetItemId;
    private String matchStatus;
    private String matchSource;
    private Boolean reviewed;
}
