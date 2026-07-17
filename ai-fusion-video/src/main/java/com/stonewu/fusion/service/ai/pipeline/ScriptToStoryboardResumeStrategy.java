package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Builds a database-verified resume plan for script-to-storyboard pipelines. */
@Component
public class ScriptToStoryboardResumeStrategy implements PipelineResumeStrategy {

    private final ScriptService scripts;
    private final StoryboardService storyboards;
    private final PipelineJsonSnapshot snapshots;

    public ScriptToStoryboardResumeStrategy(
            ScriptService scripts,
            StoryboardService storyboards,
            PipelineJsonSnapshot snapshots) {
        this.scripts = scripts;
        this.storyboards = storyboards;
        this.snapshots = snapshots;
    }

    @Override
    public boolean supports(String agentType) {
        return "script_to_storyboard".equals(agentType);
    }

    @Override
    public PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints) {
        AiChatReqVO request = snapshots.deserialize(run.getRequestJson(), AiChatReqVO.class);
        Long scriptId = contextId(request, "scriptId");
        Long storyboardId = contextId(request, "storyboardId");
        List<ScriptEpisode> episodes = scripts.listEpisodes(scriptId);
        Set<Integer> all = new TreeSet<>();
        for (ScriptEpisode episode : episodes) {
            all.add(episode.getEpisodeNumber());
        }

        Set<Integer> storyboardDone = verifiedStoryboardEpisodes(
                storyboardId, episodes, checkpoints);
        List<String> completed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        addRange(completed, storyboardDone, "分镜");
        addRange(pending, subtract(all, storyboardDone), "分镜");
        List<String> constraints = storyboardDone.isEmpty()
                ? List.of()
                : List.of("不得重新生成" + formatRange(storyboardDone) + "分镜");
        return new PipelineResumePlan(completed, pending, constraints);
    }

    private Set<Integer> verifiedStoryboardEpisodes(
            Long storyboardId,
            List<ScriptEpisode> episodes,
            List<PipelineCheckpoint> checkpoints) {
        Map<Long, PipelineCheckpoint> writers = new HashMap<>();
        for (PipelineCheckpoint checkpoint : checkpoints) {
            if (!"episode_storyboard_writer".equals(checkpoint.getToolName())
                    || checkpoint.getStatus() != PipelineCheckpointStatus.SUCCEEDED) {
                continue;
            }
            try {
                Long episodeId = JSONUtil.parseObj(checkpoint.getInputJson()).getLong("scriptEpisodeId");
                writers.put(episodeId, checkpoint);
            } catch (RuntimeException ignored) {
                // Invalid historical output is pending and will be replayed.
            }
        }
        Map<Long, Long> itemCounts = storyboards.listItems(storyboardId).stream()
                .filter(item -> item.getStoryboardEpisodeId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> item.getStoryboardEpisodeId(),
                        java.util.stream.Collectors.counting()));
        Set<Integer> completed = new TreeSet<>();
        for (ScriptEpisode episode : episodes) {
            PipelineCheckpoint checkpoint = writers.get(episode.getId());
            StoryboardEpisode storyboardEpisode = storyboards.getEpisodeByScriptEpisode(storyboardId, episode.getId());
            if (checkpoint == null || storyboardEpisode == null) {
                continue;
            }
            try {
                var output = JSONUtil.parseObj(checkpoint.getOutputJson());
                int sceneCount = storyboards.listScenesByEpisode(storyboardEpisode.getId()).size();
                long shotCount = itemCounts.getOrDefault(storyboardEpisode.getId(), 0L);
                if ("success".equalsIgnoreCase(output.getStr("status"))
                        && episode.getId().equals(output.getLong("scriptEpisodeId"))
                        && sceneCount > 0 && sceneCount == output.getInt("sceneCount", -1)
                        && shotCount > 0 && shotCount == output.getLong("shotCount", -1L)) {
                    completed.add(episode.getEpisodeNumber());
                }
            } catch (RuntimeException ignored) {
                // Invalid historical output is pending and will be replayed.
            }
        }
        return completed;
    }

    private Long contextId(AiChatReqVO request, String key) {
        if (request.getContext() == null || request.getContext().get(key) == null) {
            throw new IllegalArgumentException("恢复分镜生成缺少 " + key);
        }
        return Long.valueOf(String.valueOf(request.getContext().get(key)));
    }

    private Set<Integer> subtract(Set<Integer> all, Set<Integer> completed) {
        Set<Integer> result = new TreeSet<>(all);
        result.removeAll(completed);
        return result;
    }

    private void addRange(List<String> target, Set<Integer> episodes, String suffix) {
        if (!episodes.isEmpty()) target.add(formatRange(episodes) + suffix);
    }

    private String formatRange(Set<Integer> numbers) {
        List<String> ranges = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        for (Integer number : new LinkedHashSet<>(numbers)) {
            if (start == null) {
                start = number;
            } else if (number != previous + 1) {
                ranges.add(start.equals(previous) ? start.toString() : start + "-" + previous);
                start = number;
            }
            previous = number;
        }
        if (start != null) ranges.add(start.equals(previous) ? start.toString() : start + "-" + previous);
        return "第" + String.join("、", ranges) + "集";
    }
}
