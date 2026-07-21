package com.stonewu.fusion.service.ai.pipeline;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.PipelineCheckpoint;
import com.stonewu.fusion.entity.ai.PipelineRun;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class StoryboardItemBatchResumeStrategy implements PipelineResumeStrategy {

    private final StoryboardService storyboards;
    private final PipelineJsonSnapshot snapshots;

    public StoryboardItemBatchResumeStrategy(
            StoryboardService storyboards,
            PipelineJsonSnapshot snapshots) {
        this.storyboards = storyboards;
        this.snapshots = snapshots;
    }

    @Override
    public boolean supports(String agentType) {
        return switch (agentType) {
            case "storyboard_narrative_expand",
                    "storyboard_action_expand",
                    "storyboard_video_gen",
                    "storyboard_video_prompt_gen" -> true;
            default -> false;
        };
    }

    @Override
    public PipelineResumePlan buildPlan(PipelineRun run, List<PipelineCheckpoint> checkpoints) {
        AiChatReqVO request = snapshots.deserialize(run.getRequestJson(), AiChatReqVO.class);
        Long storyboardId = contextId(request, "storyboardId");
        Map<Long, StoryboardItem> itemsById = storyboards.listItems(storyboardId).stream()
                .collect(Collectors.toMap(
                        StoryboardItem::getId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<Long> selected = selectedStoryboardItemIds(request, itemsById.keySet());
        Predicate<StoryboardItem> complete = completionPredicate(run.getAgentType());

        LinkedHashSet<Long> completed = selected.stream()
                .filter(itemId -> {
                    StoryboardItem item = itemsById.get(itemId);
                    return item != null && complete.test(item);
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Long> pending = selected.stream()
                .filter(itemId -> !completed.contains(itemId))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new PipelineResumePlan(
                completed.isEmpty() ? List.of() : List.of("已完成镜头: " + join(completed)),
                pending.isEmpty() ? List.of() : List.of("待处理镜头: " + join(pending)),
                List.of("只调度待处理镜头，不要重复生成已完成镜头"));
    }

    private Predicate<StoryboardItem> completionPredicate(String agentType) {
        return switch (agentType) {
            case "storyboard_narrative_expand" -> item ->
                    (StrUtil.isNotBlank(item.getGrid25ImageUrl())
                            || StrUtil.isNotBlank(item.getStoryboardImageUrl()))
                            && StrUtil.isNotBlank(item.getVideoPrompt());
            case "storyboard_action_expand" -> item ->
                    StrUtil.isNotBlank(item.getActionStoryboardImageUrl())
                            && StrUtil.isNotBlank(item.getMotionPlan())
                            && StrUtil.isNotBlank(item.getVideoPrompt());
            case "storyboard_video_gen", "storyboard_video_prompt_gen" -> item ->
                    StrUtil.isNotBlank(item.getVideoPrompt());
            default -> item -> false;
        };
    }

    private Long contextId(AiChatReqVO request, String key) {
        if (request.getContext() == null || request.getContext().get(key) == null) {
            throw new IllegalArgumentException("恢复分镜批量任务缺少 " + key);
        }
        return Long.valueOf(String.valueOf(request.getContext().get(key)));
    }

    private LinkedHashSet<Long> selectedStoryboardItemIds(AiChatReqVO request, Set<Long> allItemIds) {
        Object value = request.getContext() == null ? null : request.getContext().get("selectedStoryboardItemIds");
        if (!(value instanceof Iterable<?> iterable)) {
            return new LinkedHashSet<>(allItemIds);
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
