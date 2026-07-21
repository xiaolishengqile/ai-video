package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class StoryboardAssetMatchResumeStrategy implements PipelineResumeStrategy {

    private static final Pattern STORYBOARD_ITEM_ID_PATTERN =
            Pattern.compile("storyboardItemId\\s*[:：]\\s*(\\d+)");

    private final PipelineJsonSnapshot snapshots;

    public StoryboardAssetMatchResumeStrategy(PipelineJsonSnapshot snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public boolean supports(String agentType) {
        return "storyboard_asset_matcher".equals(agentType);
    }

    @Override
    public PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints) {
        LinkedHashSet<Long> selected = selectedStoryboardItemIds(run);
        if (selected.isEmpty()) {
            return new ToolCheckpointResumeStrategy().buildPlan(run, checkpoints);
        }

        Set<Long> completed = new LinkedHashSet<>();
        for (PipelineCheckpoint checkpoint : checkpoints) {
            if (checkpoint.getStatus() != PipelineCheckpointStatus.SUCCEEDED) {
                continue;
            }
            Long itemId = switch (checkpoint.getToolName()) {
                case "match_storyboard_item_assets" -> extractStoryboardItemIdFromMatchInput(checkpoint.getInputJson());
                case "update_storyboard_item_assets" -> parse(checkpoint.getInputJson()).getLong("storyboardItemId");
                default -> null;
            };
            if (itemId != null) {
                completed.add(itemId);
            }
        }

        LinkedHashSet<Long> completedSelected = selected.stream()
                .filter(completed::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Long> pending = selected.stream()
                .filter(itemId -> !completed.contains(itemId))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new PipelineResumePlan(
                completedSelected.isEmpty() ? List.of() : List.of("已匹配镜头: " + join(completedSelected)),
                pending.isEmpty() ? List.of() : List.of("待匹配镜头: " + join(pending)),
                List.of("只调度待匹配镜头，不要重复处理已匹配镜头"));
    }

    private LinkedHashSet<Long> selectedStoryboardItemIds(PipelineRun run) {
        AiChatReqVO request = snapshots.deserialize(run.getRequestJson(), AiChatReqVO.class);
        Map<String, Object> context = request.getContext();
        if (context == null) {
            return new LinkedHashSet<>();
        }
        Object value = context.get("selectedStoryboardItemIds");
        if (!(value instanceof Iterable<?> iterable)) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object item : iterable) {
            Long id = toLong(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Long extractStoryboardItemIdFromMatchInput(String inputJson) {
        JSONObject input = parse(inputJson);
        Long direct = input.getLong("storyboardItemId");
        if (direct != null) {
            return direct;
        }
        String message = input.getStr("message");
        if (message == null) {
            return null;
        }
        Matcher matcher = STORYBOARD_ITEM_ID_PATTERN.matcher(message);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private JSONObject parse(String inputJson) {
        try {
            return JSONUtil.parseObj(inputJson);
        } catch (RuntimeException error) {
            return JSONUtil.createObj();
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String join(Set<Long> ids) {
        return ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }
}
