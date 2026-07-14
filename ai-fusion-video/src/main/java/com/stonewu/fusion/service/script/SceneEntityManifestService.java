package com.stonewu.fusion.service.script;

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

    public SceneEntityManifest resolve(Long projectId, Long userId, Integer episodeNumber, SceneEntityManifest requested) {
        if (projectId == null || userId == null || episodeNumber == null) {
            throw new IllegalArgumentException("projectId, userId and episodeNumber are required");
        }
        if (requested == null) {
            throw new IllegalArgumentException("entity manifest is required");
        }

        List<SceneEntity> entities = requested.entities();
        Set<String> keys = new HashSet<>();
        for (SceneEntity entity : entities) {
            validate(entity);
            if (!keys.add(entity.key())) {
                throw new IllegalArgumentException("duplicate entity key: " + entity.key());
            }
        }
        Set<Integer> retained = selectWithinLimits(entities);

        List<SceneEntity> resolved = IntStream.range(0, entities.size())
                .mapToObj(index -> resolve(entities.get(index), projectId, userId, episodeNumber, retained.contains(index)))
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

    private SceneEntity resolve(SceneEntity entity, Long projectId, Long userId, Integer episodeNumber, boolean retained) {
        if ("atmospheric".equals(entity.importance())) {
            return withIds(entity, "atmospheric", null, null, "atmospheric");
        }
        if (!retained) {
            return withIds(entity, "atmospheric", null, null, "filtered_limit");
        }

        Asset asset = assetService.findByProjectEpisodeTypeAndName(projectId, episodeNumber,
                entity.assetType(), entity.name());
        if (asset == null) {
            AssetService.FindOrCreateResult created = assetService.findOrCreate(Asset.builder()
                    .projectId(projectId).episodeNumber(episodeNumber).userId(userId)
                    .type(entity.assetType()).name(entity.name()).sourceType(2).build());
            AssetItem initialItem = assetService.listItems(created.asset().getId()).stream()
                    .filter(item -> "initial".equals(item.getItemType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("补建资产缺少初始子资产"));
            return withIds(entity, entity.importance(), created.asset().getId(), initialItem.getId(),
                    "auto_created_episode_catalog");
        }

        AssetItem initialItem = assetService.listItems(asset.getId()).stream()
                .filter(item -> "initial".equals(item.getItemType()))
                .findFirst()
                .orElse(null);
        if (initialItem == null) {
            return withIds(entity, entity.importance(), null, null, "unmatched_episode_catalog");
        }
        return withIds(entity, entity.importance(), asset.getId(), initialItem.getId(), "matched");
    }

    private void validate(SceneEntity entity) {
        if (entity == null || entity.key() == null || entity.key().isBlank()) {
            throw new IllegalArgumentException("entity key is required");
        }
        if (entity.name() == null || entity.name().isBlank()) {
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
