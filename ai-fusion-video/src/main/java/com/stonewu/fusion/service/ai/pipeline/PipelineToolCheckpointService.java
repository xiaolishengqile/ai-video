package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.springframework.stereotype.Service;

@Service
public class PipelineToolCheckpointService {

    private final PipelineRunRepository runs;
    private final PipelineCheckpointRepository checkpoints;
    private final PipelineFailureClassifier classifier;
    private final ScriptService scripts;
    private final StoryboardService storyboards;

    public PipelineToolCheckpointService(
            PipelineRunRepository runs,
            PipelineCheckpointRepository checkpoints,
            PipelineFailureClassifier classifier,
            ScriptService scripts,
            StoryboardService storyboards) {
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.classifier = classifier;
        this.scripts = scripts;
        this.storyboards = storyboards;
    }

    public CheckpointDecision beforeExecute(
            PipelineExecutionContext context,
            CheckpointDescriptor descriptor,
            String inputJson) {
        Long pipelineRunId = resolvePipelineRunId(context);
        PipelineCheckpoint checkpoint = checkpoints.find(pipelineRunId, descriptor.checkpointKey());
        if (checkpoint == null) {
            checkpoints.upsertRunning(pipelineRunId, descriptor, inputJson);
            return CheckpointDecision.execute();
        }
        if (checkpoint.getStatus() == PipelineCheckpointStatus.SUCCEEDED) {
            String verificationError = verifySuccessfulResult(
                    descriptor, inputJson, parse(checkpoint.getOutputJson()));
            if (verificationError != null
                    && descriptor.replayPolicy() == CheckpointReplayPolicy.SAFE_REPLAY) {
                checkpoints.upsertRunning(pipelineRunId, descriptor, inputJson);
                return CheckpointDecision.execute();
            }
            if (verificationError != null) {
                return CheckpointDecision.requireManual(verificationError);
            }
            return CheckpointDecision.returnStored(checkpoint.getOutputJson());
        }
        if (checkpoint.getStatus() == PipelineCheckpointStatus.RUNNING) {
            return CheckpointDecision.requireManual("检查点仍在执行，禁止并发重复调用");
        }
        if (checkpoint.getStatus() == PipelineCheckpointStatus.UNKNOWN
                && descriptor.replayPolicy() != CheckpointReplayPolicy.SAFE_REPLAY) {
            return CheckpointDecision.requireManual("检查点状态无法安全确认，需要人工处理");
        }
        if (checkpoint.getStatus() == PipelineCheckpointStatus.FAILED
                && descriptor.replayPolicy() == CheckpointReplayPolicy.NEVER_REPLAY) {
            return CheckpointDecision.requireManual("该工具禁止自动重放，需要人工处理");
        }
        checkpoints.upsertRunning(pipelineRunId, descriptor, inputJson);
        return CheckpointDecision.execute();
    }

    public String recordResult(
            PipelineExecutionContext context,
            CheckpointDescriptor descriptor,
            String result) {
        return recordResult(context, descriptor, null, result);
    }

    public String recordResult(
            PipelineExecutionContext context,
            CheckpointDescriptor descriptor,
            String inputJson,
            String result) {
        Long pipelineRunId = resolvePipelineRunId(context);
        result = normalizeEpisodeStoryboardWriterResult(descriptor, inputJson, result);
        JSONObject json = parse(result);
        String status = json.getStr("status", "");
        if ("error".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || Boolean.FALSE.equals(json.getBool("success"))) {
            String message = json.getStr("message", "工具返回业务错误");
            checkpoints.markFailed(
                    pipelineRunId,
                    descriptor.checkpointKey(),
                    new PipelineFailure(PipelineFailureCategory.BUSINESS_ERROR, null, message, false));
            return result;
        }
        String verificationError = verifySuccessfulResult(descriptor, inputJson, json);
        if (verificationError != null) {
            checkpoints.markFailed(
                    pipelineRunId,
                    descriptor.checkpointKey(),
                    new PipelineFailure(PipelineFailureCategory.BUSINESS_ERROR, null, verificationError, false));
            return JSONUtil.createObj().set("status", "error").set("message", verificationError).toString();
        }
        checkpoints.markSucceeded(pipelineRunId, descriptor.checkpointKey(), result);
        return result;
    }

    public void recordFailure(
            PipelineExecutionContext context,
            CheckpointDescriptor descriptor,
            Throwable error) {
        checkpoints.markFailed(
                resolvePipelineRunId(context),
                descriptor.checkpointKey(),
                classifier.classify(error));
    }

    private Long resolvePipelineRunId(PipelineExecutionContext context) {
        return context.pipelineRunId() != null
                ? context.pipelineRunId()
                : runs.requireByRunId(context.runId()).getId();
    }

    private JSONObject parse(String result) {
        try {
            return JSONUtil.parseObj(result);
        } catch (RuntimeException error) {
            return JSONUtil.createObj();
        }
    }

    private String verifyEpisodeSceneWriter(
            CheckpointDescriptor descriptor,
            String inputJson,
            JSONObject output) {
        if (!"episode_scene_writer".equals(descriptor.toolName())) {
            return null;
        }
        JSONObject input = parse(inputJson);
        Long requestedEpisodeId = input.getLong("scriptEpisodeId");
        Long completedEpisodeId = output.getLong("scriptEpisodeId");
        Integer expected = output.getInt("expectedSceneCount");
        Integer saved = output.getInt("savedSceneCount");
        if (!"success".equalsIgnoreCase(output.getStr("status"))
                || requestedEpisodeId == null
                || !requestedEpisodeId.equals(completedEpisodeId)
                || expected == null || expected <= 0
                || saved == null || !expected.equals(saved)) {
            return "分集子 Agent 缺少有效的结构化完成证明";
        }
        int actual = scripts.listScenesByEpisode(requestedEpisodeId).size();
        return actual == expected ? null
                : "分集实际场次数为 " + actual + "，与声明的 " + expected + " 不一致";
    }

    private String verifySuccessfulResult(
            CheckpointDescriptor descriptor,
            String inputJson,
            JSONObject output) {
        String sceneWriterError = verifyEpisodeSceneWriter(descriptor, inputJson, output);
        return sceneWriterError != null
                ? sceneWriterError
                : verifyEpisodeStoryboardWriter(descriptor, inputJson, output);
    }

    private String verifyEpisodeStoryboardWriter(
            CheckpointDescriptor descriptor,
            String inputJson,
            JSONObject output) {
        if (!"episode_storyboard_writer".equals(descriptor.toolName())) {
            return null;
        }
        JSONObject input = parse(inputJson);
        Long requestedEpisodeId = input.getLong("scriptEpisodeId");
        Long storyboardId = input.getLong("storyboardId");
        Integer declaredScenes = output.getInt("sceneCount");
        Integer declaredShots = output.getInt("shotCount");
        String status = output.getStr("status");
        if ("blocked_missing_assets".equalsIgnoreCase(status)) {
            return requestedEpisodeId != null
                    && requestedEpisodeId.equals(output.getLong("scriptEpisodeId"))
                    && storyboardId != null
                    ? null
                    : "分镜子 Agent 缺少有效的待补资产证明";
        }
        if (!"success".equalsIgnoreCase(output.getStr("status"))
                || requestedEpisodeId == null
                || !requestedEpisodeId.equals(output.getLong("scriptEpisodeId"))
                || storyboardId == null
                || declaredScenes == null || declaredScenes <= 0
                || declaredShots == null || declaredShots <= 0) {
            return "分镜子 Agent 缺少有效的结构化完成证明";
        }
        var episode = storyboards.getEpisodeByScriptEpisode(storyboardId, requestedEpisodeId);
        if (episode == null) {
            return "分镜实际数量无法验证：未找到对应分镜集";
        }
        int actualScenes = storyboards.listScenesByEpisode(episode.getId()).size();
        long actualShots = storyboards.listItems(storyboardId).stream()
                .filter(item -> episode.getId().equals(item.getStoryboardEpisodeId()))
                .count();
        return actualScenes == declaredScenes && actualShots == declaredShots
                ? null
                : "分镜实际数量为 " + actualScenes + " 场、" + actualShots
                        + " 镜，与声明的 " + declaredScenes + " 场、" + declaredShots + " 镜不一致";
    }

    private String normalizeEpisodeStoryboardWriterResult(
            CheckpointDescriptor descriptor,
            String inputJson,
            String result) {
        if (!"episode_storyboard_writer".equals(descriptor.toolName())) {
            return result;
        }
        JSONObject parsed = parse(result);
        if (!parsed.isEmpty()) {
            return result;
        }
        JSONObject input = parse(inputJson);
        Long requestedEpisodeId = input.getLong("scriptEpisodeId");
        Long storyboardId = input.getLong("storyboardId");
        if (requestedEpisodeId == null || storyboardId == null) {
            return result;
        }
        StoryboardEpisode episode = storyboards.getEpisodeByScriptEpisode(storyboardId, requestedEpisodeId);
        if (episode == null) {
            return result;
        }
        int actualScenes = storyboards.listScenesByEpisode(episode.getId()).size();
        long actualShots = storyboards.listItems(storyboardId).stream()
                .filter(item -> episode.getId().equals(item.getStoryboardEpisodeId()))
                .count();
        if (actualScenes > 0 && actualShots > 0) {
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("scriptEpisodeId", requestedEpisodeId)
                    .set("sceneCount", actualScenes)
                    .set("shotCount", actualShots)
                    .set("message", "已根据数据库落库结果自动确认分镜完成")
                    .toString();
        }
        if (isMissingAssetResult(result)) {
            return JSONUtil.createObj()
                    .set("status", "blocked_missing_assets")
                    .set("scriptEpisodeId", requestedEpisodeId)
                    .set("sceneCount", actualScenes)
                    .set("shotCount", actualShots)
                    .set("requiresManualAssetCompletion", true)
                    .set("message", result)
                    .toString();
        }
        return result;
    }

    private boolean isMissingAssetResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        return result.contains("blocked_missing_assets")
                || result.contains("待补资产")
                || result.contains("缺少可用图片子资产")
                || (result.contains("缺少") && result.contains("核心场景"));
    }
}
