package com.stonewu.fusion.service.script.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 场次实体清单的持久化格式。
 */
public record SceneEntityManifest(int version, List<SceneEntity> entities) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SceneEntityManifest {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public static SceneEntityManifest fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new SceneEntityManifest(1, List.of());
        }
        try {
            return OBJECT_MAPPER.readValue(json, SceneEntityManifest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid scene entity manifest JSON", e);
        }
    }

    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize scene entity manifest", e);
        }
    }
}
