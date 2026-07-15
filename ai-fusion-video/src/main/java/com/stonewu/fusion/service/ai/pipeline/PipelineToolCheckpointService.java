package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.service.script.ScriptService;
import org.springframework.stereotype.Service;

@Service
public class PipelineToolCheckpointService {

    private final PipelineRunRepository runs;
    private final PipelineCheckpointRepository checkpoints;
    private final PipelineFailureClassifier classifier;
    private final ScriptService scripts;

    public PipelineToolCheckpointService(
            PipelineRunRepository runs,
            PipelineCheckpointRepository checkpoints,
            PipelineFailureClassifier classifier,
            ScriptService scripts) {
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.classifier = classifier;
        this.scripts = scripts;
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
        String verificationError = verifyEpisodeSceneWriter(descriptor, inputJson, json);
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
}
