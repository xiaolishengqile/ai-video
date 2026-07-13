package com.stonewu.fusion.service.script.model;

/**
 * 场次中识别出的可复用实体及其已解析资产。
 */
public record SceneEntity(
        String key,
        String name,
        String assetType,
        String entitySubtype,
        String importance,
        boolean defaultForShots,
        Long assetId,
        Long assetItemId,
        String source
) {
}
