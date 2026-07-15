package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class PipelineToolCheckpointPolicyRegistry {

    private static final String DIGEST_FIELD = "#digest";
    private static final Set<String> READ_PREFIXES = Set.of("get_", "list_", "query_", "search_");
    private final Map<String, PipelineToolCheckpointPolicy> policies = new HashMap<>();

    public PipelineToolCheckpointPolicyRegistry() {
        register("update_script", "script", CheckpointReplayPolicy.SAFE_REPLAY, "scriptId");
        register("update_script_info", "script", CheckpointReplayPolicy.SAFE_REPLAY, "scriptId");
        register("save_script_episode", "episode", CheckpointReplayPolicy.SAFE_REPLAY,
                "scriptId", "episodeNumber");
        register("save_script_scene_items", "episode", CheckpointReplayPolicy.VERIFY_BEFORE_REPLAY,
                "scriptEpisodeId", "episode_version", DIGEST_FIELD);
        register("run_script_asset_prebinding", "episode", CheckpointReplayPolicy.SAFE_REPLAY,
                "scriptEpisodeId");
        register("create_project_asset_catalog_snapshot", "episode", CheckpointReplayPolicy.SAFE_REPLAY,
                "projectId", "scriptEpisodeId");
        register("save_storyboard_episode", "storyboard_episode", CheckpointReplayPolicy.SAFE_REPLAY,
                "storyboardId", "scriptEpisodeId");
        register("save_storyboard_scene_shots", "storyboard_scene", CheckpointReplayPolicy.VERIFY_BEFORE_REPLAY,
                "storyboardEpisodeId", "scriptSceneItemId", "sceneNumber");
        register("insert_storyboard_item", "storyboard_item", CheckpointReplayPolicy.NEVER_REPLAY,
                "storyboardId", DIGEST_FIELD);
        register("update_storyboard_item_frame", "storyboard_item", CheckpointReplayPolicy.SAFE_REPLAY,
                "storyboardItemId", "frameType");
        register("update_storyboard_item_video", "storyboard_item", CheckpointReplayPolicy.SAFE_REPLAY,
                "storyboardItemId");
        register("update_storyboard_item_workflow", "storyboard_item", CheckpointReplayPolicy.SAFE_REPLAY,
                "storyboardItemId");
        register("create_asset", "asset", CheckpointReplayPolicy.SAFE_REPLAY,
                "projectId", "type", "name");
        register("batch_create_assets", "asset_batch", CheckpointReplayPolicy.SAFE_REPLAY,
                "projectId", DIGEST_FIELD);
        register("update_asset", "asset", CheckpointReplayPolicy.SAFE_REPLAY, "assetId");
        register("add_asset_item", "asset_item", CheckpointReplayPolicy.NEVER_REPLAY,
                "assetId", DIGEST_FIELD);
        register("batch_create_asset_items", "asset_item_batch", CheckpointReplayPolicy.SAFE_REPLAY,
                "assetId", DIGEST_FIELD);
        register("update_asset_image", "asset_item", CheckpointReplayPolicy.SAFE_REPLAY,
                "assetId", "itemId");
        register("resolve_scene_entity_manifest", "episode", CheckpointReplayPolicy.SAFE_REPLAY,
                "scriptEpisodeId", DIGEST_FIELD);
        policies.put("manage_script_scenes", input -> {
            JSONObject json = parse(input);
            CheckpointReplayPolicy replay = "delete".equals(json.getStr("action"))
                    ? CheckpointReplayPolicy.SAFE_REPLAY
                    : CheckpointReplayPolicy.NEVER_REPLAY;
            return descriptor("manage_script_scenes", "script_scene", replay, input,
                    "action", "scriptEpisodeId", DIGEST_FIELD);
        });
        register("update_script_scene", "script_scene", CheckpointReplayPolicy.SAFE_REPLAY,
                "scriptSceneItemId");
        register("generate_image", "generation", CheckpointReplayPolicy.NEVER_REPLAY, DIGEST_FIELD);
        register("generate_video", "generation", CheckpointReplayPolicy.NEVER_REPLAY, DIGEST_FIELD);

        for (String subAgent : Set.of(
                "episode_scene_writer",
                "episode_script_creator",
                "storyboard_asset_preprocessor",
                "episode_storyboard_writer",
                "generate_asset_image",
                "generate_storyboard_frame",
                "generate_storyboard_narrative_material",
                "generate_storyboard_action_material",
                "generate_storyboard_video")) {
            register(subAgent, "sub_agent", CheckpointReplayPolicy.SAFE_REPLAY, DIGEST_FIELD);
        }
    }

    public Optional<CheckpointDescriptor> describe(String toolName, String inputJson) {
        PipelineToolCheckpointPolicy policy = policies.get(toolName);
        return policy == null ? Optional.empty() : Optional.of(policy.describe(inputJson));
    }

    public boolean isReadOnly(String toolName) {
        return toolName != null && READ_PREFIXES.stream().anyMatch(toolName::startsWith);
    }

    public boolean isClassified(String toolName) {
        return policies.containsKey(toolName);
    }

    private void register(
            String toolName,
            String scopeType,
            CheckpointReplayPolicy replayPolicy,
            String... fields) {
        policies.put(toolName, input -> descriptor(toolName, scopeType, replayPolicy, input, fields));
    }

    private CheckpointDescriptor descriptor(
            String toolName,
            String scopeType,
            CheckpointReplayPolicy replayPolicy,
            String input,
            String... fields) {
        JSONObject json = parse(input);
        StringBuilder scopeId = new StringBuilder();
        for (String field : fields) {
            Object value = DIGEST_FIELD.equals(field) ? digest(input) : json.get(field);
            if (!scopeId.isEmpty()) {
                scopeId.append(':');
            }
            scopeId.append(value == null ? "missing" : value);
        }
        String stableScopeId = scopeId.toString();
        return new CheckpointDescriptor(
                toolName + ":" + stableScopeId,
                toolName,
                scopeType,
                stableScopeId,
                replayPolicy);
    }

    private JSONObject parse(String input) {
        try {
            return JSONUtil.parseObj(input);
        } catch (RuntimeException error) {
            return JSONUtil.createObj();
        }
    }

    private String digest(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(input).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
