package com.stonewu.fusion.controller.asset.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AssetFolderImportPreviewReqVO(
        @NotNull Long projectId,
        @NotBlank String type,
        @NotNull List<@Valid File> files) {
    public record File(@NotBlank String relativePath, @NotBlank String originalName) {
    }
}
