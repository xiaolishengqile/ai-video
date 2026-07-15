package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.service.script.ScriptService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class ScriptFullParseResumeStrategy implements PipelineResumeStrategy {

    private final ScriptService scripts;
    private final PipelineJsonSnapshot snapshots;

    public ScriptFullParseResumeStrategy(ScriptService scripts, PipelineJsonSnapshot snapshots) {
        this.scripts = scripts;
        this.snapshots = snapshots;
    }

    @Override
    public boolean supports(String agentType) {
        return "script_full_parse".equals(agentType);
    }

    @Override
    public PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints) {
        AiChatReqVO request = snapshots.deserialize(run.getRequestJson(), AiChatReqVO.class);
        Long scriptId = extractScriptId(request);
        Script script = scripts.getById(scriptId);
        List<ScriptEpisode> episodes = scripts.listEpisodes(scriptId);
        Map<Long, Integer> episodeNumbersById = new HashMap<>();
        Set<Integer> saved = new TreeSet<>();
        for (ScriptEpisode episode : episodes) {
            episodeNumbersById.put(episode.getId(), episode.getEpisodeNumber());
            saved.add(episode.getEpisodeNumber());
        }
        int total = resolveTotal(script, saved);
        Set<Integer> all = range(1, total);
        Set<Integer> prebound = succeededEpisodes(
                checkpoints, "run_script_asset_prebinding", episodeNumbersById);
        Set<Integer> snapshotted = succeededEpisodes(
                checkpoints, "create_project_asset_catalog_snapshot", episodeNumbersById);
        Set<Integer> withScenes = new TreeSet<>();
        for (ScriptEpisode episode : episodes) {
            if (!scripts.listScenesByEpisode(episode.getId()).isEmpty()) {
                withScenes.add(episode.getEpisodeNumber());
            }
        }

        List<String> completed = new ArrayList<>();
        if (hasSucceeded(checkpoints, "update_script_info")) {
            completed.add("剧本元信息");
        }
        addRange(completed, saved, "");
        addRange(completed, prebound, "资产预绑定");
        addRange(completed, withScenes, "场次");
        addRange(completed, snapshotted, "快照");

        List<String> pending = new ArrayList<>();
        addRange(pending, subtract(all, saved), "");
        addRange(pending, subtract(all, prebound), "资产预绑定");
        addRange(pending, subtract(all, withScenes), "场次");
        addRange(pending, subtract(all, snapshotted), "快照");

        List<String> constraints = saved.isEmpty()
                ? List.of()
                : List.of("不得删除或重新创建" + formatRange(saved));
        return new PipelineResumePlan(completed, pending, constraints);
    }

    private Long extractScriptId(AiChatReqVO request) {
        if (request.getContext() == null || request.getContext().get("scriptId") == null) {
            throw new IllegalArgumentException("恢复剧本解析缺少 scriptId");
        }
        return Long.valueOf(String.valueOf(request.getContext().get("scriptId")));
    }

    private int resolveTotal(Script script, Set<Integer> saved) {
        if (script.getTotalEpisodes() != null && script.getTotalEpisodes() > 0) {
            return script.getTotalEpisodes();
        }
        return saved.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private Set<Integer> succeededEpisodes(
            List<PipelineCheckpoint> checkpoints,
            String toolName,
            Map<Long, Integer> episodeNumbersById) {
        Set<Integer> completed = new TreeSet<>();
        for (PipelineCheckpoint checkpoint : checkpoints) {
            if (!toolName.equals(checkpoint.getToolName())
                    || checkpoint.getStatus() != PipelineCheckpointStatus.SUCCEEDED) {
                continue;
            }
            try {
                Long episodeId = JSONUtil.parseObj(checkpoint.getInputJson()).getLong("scriptEpisodeId");
                Integer number = episodeNumbersById.get(episodeId);
                if (number != null) {
                    completed.add(number);
                }
            } catch (RuntimeException ignored) {
                // 无法用旧输入验证的检查点不计为完成。
            }
        }
        return completed;
    }

    private boolean hasSucceeded(List<PipelineCheckpoint> checkpoints, String toolName) {
        return checkpoints.stream().anyMatch(checkpoint ->
                toolName.equals(checkpoint.getToolName())
                        && checkpoint.getStatus() == PipelineCheckpointStatus.SUCCEEDED);
    }

    private Set<Integer> range(int start, int end) {
        Set<Integer> result = new TreeSet<>();
        for (int value = start; value <= end; value++) {
            result.add(value);
        }
        return result;
    }

    private Set<Integer> subtract(Set<Integer> all, Set<Integer> completed) {
        Set<Integer> result = new TreeSet<>(all);
        result.removeAll(completed);
        return result;
    }

    private void addRange(List<String> target, Set<Integer> episodes, String suffix) {
        if (!episodes.isEmpty()) {
            target.add(formatRange(episodes) + suffix);
        }
    }

    private String formatRange(Set<Integer> numbers) {
        List<String> ranges = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        for (Integer number : new LinkedHashSet<>(numbers)) {
            if (start == null) {
                start = number;
            } else if (number != previous + 1) {
                ranges.add(formatSegment(start, previous));
                start = number;
            }
            previous = number;
        }
        if (start != null) {
            ranges.add(formatSegment(start, previous));
        }
        return "第" + String.join("、", ranges) + "集";
    }

    private String formatSegment(int start, int end) {
        return start == end ? Integer.toString(start) : start + "-" + end;
    }
}
