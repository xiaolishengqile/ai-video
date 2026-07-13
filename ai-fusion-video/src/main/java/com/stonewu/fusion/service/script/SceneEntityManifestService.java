package com.stonewu.fusion.service.script;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Resolves reusable scene entities to project assets and their initial items. */
@Service
@RequiredArgsConstructor
public class SceneEntityManifestService {

    private static final Set<String> ASSET_TYPES = Set.of("character", "scene", "prop");
    private static final Set<String> IMPORTANCE = Set.of("core", "supporting", "atmospheric");

    private final AssetService assetService;

    public SceneEntityManifest resolve(Long projectId, Long userId, SceneEntityManifest requested) {
        if (projectId == null || userId == null) {
            throw new IllegalArgumentException("projectId and userId are required");
        }
        if (requested == null) {
            throw new IllegalArgumentException("entity manifest is required");
        }

        List<SceneEntity> entities = requested.entities();
        entities.forEach(this::validate);
        Set<Integer> retained = selectWithinLimits(entities);

        List<SceneEntity> resolved = IntStream.range(0, entities.size())
                .mapToObj(index -> resolve(entities.get(index), projectId, userId, retained.contains(index)))
                .toList();
        return new SceneEntityManifest(requested.version(), resolved);
    }

    private Set<Integer> selectWithinLimits(List<SceneEntity> entities) {
        Set<Integer> retained = new HashSet<>();
        int[] counts = new int[3];
        IntStream.range(0, entities.size()).boxed()
                .filter(index -> !"atmospheric".equals(entities.get(index).importance()))
                .sorted(Comparator.comparingInt(index -> "core".equals(entities.get(index).importance()) ? 0 : 1))
                .forEach(index -> {
                    int typeIndex = typeIndex(entities.get(index).assetType());
                    int limit = typeIndex == 1 ? 1 : 3;
                    if (counts[typeIndex] < limit) {
                        counts[typeIndex]++;
                        retained.add(index);
                    }
                });
        return retained;
    }

    private SceneEntity resolve(SceneEntity entity, Long projectId, Long userId, boolean retained) {
        if ("atmospheric".equals(entity.importance())) {
            return withIds(entity, "atmospheric", null, null, "atmospheric");
        }
        if (!retained) {
            return withIds(entity, "atmospheric", null, null, "filtered_limit");
        }

        Asset asset = assetService.findByProjectTypeAndName(projectId, entity.assetType(), entity.name());
        String source = "reused";
        if (asset == null) {
            asset = assetService.create(Asset.builder()
                    .projectId(projectId)
                    .userId(userId)
                    .type(entity.assetType())
                    .name(entity.name())
                    .properties(JSONUtil.createObj().set("entitySubtype", entity.entitySubtype()).toString())
                    .sourceType(2)
                    .build());
            source = "auto_created";
        }

        AssetItem initialItem = assetService.listItems(asset.getId()).stream()
                .filter(item -> "initial".equals(item.getItemType()))
                .findFirst()
                .orElse(null);
        if (initialItem == null) {
            initialItem = assetService.createItem(AssetItem.builder()
                        .assetId(asset.getId())
                        .itemType("initial")
                        .name(asset.getName())
                        .sortOrder(0)
                        .sourceType(2)
                        .build());
        }
        return withIds(entity, entity.importance(), asset.getId(), initialItem.getId(), source);
    }

    private void validate(SceneEntity entity) {
        if (entity == null || entity.name() == null || entity.name().isBlank()) {
            throw new IllegalArgumentException("entity name is required");
        }
        if (!ASSET_TYPES.contains(entity.assetType())) {
            throw new IllegalArgumentException("unsupported entity assetType: " + entity.assetType());
        }
        if (entity.entitySubtype() == null || !entity.entitySubtype().matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException("invalid entitySubtype: " + entity.entitySubtype());
        }
        if (!IMPORTANCE.contains(entity.importance())) {
            throw new IllegalArgumentException("unsupported entity importance: " + entity.importance());
        }
        if (("vehicle".equals(entity.entitySubtype()) || "wreckage".equals(entity.entitySubtype()))
                && !"prop".equals(entity.assetType())) {
            throw new IllegalArgumentException(entity.entitySubtype() + " must be a prop");
        }
        if ("collective".equals(entity.entitySubtype())
                && !("character".equals(entity.assetType()) || "prop".equals(entity.assetType()))) {
            throw new IllegalArgumentException("collective must be a character or prop");
        }
    }

    private SceneEntity withIds(SceneEntity entity, String importance, Long assetId, Long assetItemId, String source) {
        return new SceneEntity(entity.key(), entity.name(), entity.assetType(), entity.entitySubtype(), importance,
                "core".equals(importance), assetId, assetItemId, source);
    }

    private int typeIndex(String type) {
        return switch (type) {
            case "character" -> 0;
            case "scene" -> 1;
            case "prop" -> 2;
            default -> throw new IllegalArgumentException("unsupported entity assetType: " + type);
        };
    }
}
