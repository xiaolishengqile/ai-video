package com.stonewu.fusion.service.storyboard;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.controller.storyboard.vo.StoryboardGridImageGenerateReqVO;
import com.stonewu.fusion.controller.storyboard.vo.StoryboardGridImageGenerateRespVO;
import com.stonewu.fusion.controller.storyboard.vo.StoryboardWorkflowUpdateReqVO;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分镜宫格图直连生成服务。
 * <p>
 * 只负责把已存在的宫格提示词提交到图片队列并回填结果，不经过文字模型派发。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardGridImageGenerationService {

    private static final long WAIT_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final String MODE_ACTION = "action";
    private static final String MODE_NARRATIVE = "narrative";

    private final StoryboardService storyboardService;
    private final AssetService assetService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final ImageGenerationService imageGenerationService;

    @Value("${app.storyboard.grid-image.max-concurrent:10}")
    private int maxConcurrent;

    private final Set<Long> runningItemIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ExecutorService ownedExecutor;
    private Executor gridImageExecutor;

    @PostConstruct
    void initExecutor() {
        int limit = Math.max(1, maxConcurrent);
        AtomicInteger counter = new AtomicInteger(1);
        ownedExecutor = Executors.newFixedThreadPool(limit, r -> {
            Thread thread = new Thread(r, "storyboard-grid-image-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        gridImageExecutor = ownedExecutor;
        log.info("[StoryboardGridImage] 宫格图直连生成并发: {}", limit);
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    public StoryboardGridImageGenerateRespVO submitGenerate(
            Long storyboardId,
            StoryboardGridImageGenerateReqVO reqVO,
            Long userId
    ) {
        boolean force = Boolean.TRUE.equals(reqVO != null ? reqVO.getForce() : null);
        Map<Long, List<String>> referenceOverrides = reqVO != null && reqVO.getReferenceImageUrlsByItemId() != null
                ? reqVO.getReferenceImageUrlsByItemId()
                : Map.of();
        Storyboard storyboard = storyboardService.getById(storyboardId);
        List<StoryboardItem> items = selectItems(storyboardId, reqVO != null ? reqVO.getStoryboardItemIds() : null);
        Counters counters = new Counters(items.size());

        for (StoryboardItem item : items) {
            Eligibility eligibility = checkEligibility(item, force);
            if (eligibility.skipReason != null) {
                counters.addSkip(eligibility.skipReason);
                continue;
            }
            if (!runningItemIds.add(item.getId())) {
                counters.skippedRunningCount++;
                continue;
            }
            gridImageExecutor.execute(() -> {
                try {
                    generateOne(item.getId(), storyboard.getProjectId(), userId, force,
                            referenceOverrides.getOrDefault(item.getId(), List.of()));
                } finally {
                    runningItemIds.remove(item.getId());
                }
            });
            counters.submittedCount++;
        }

        return counters.toResp();
    }

    void generateOne(
            Long storyboardItemId,
            Long projectId,
            Long userId,
            boolean force,
            List<String> referenceOverrideUrls
    ) {
        StoryboardItem item = storyboardService.getItemById(storyboardItemId);
        Eligibility eligibility = checkEligibility(item, force);
        if (eligibility.skipReason != null) {
            log.info("[StoryboardGridImage] 执行前跳过镜头: itemId={}, reason={}", storyboardItemId, eligibility.skipReason);
            return;
        }

        String mode = eligibility.mode;
        String prompt = eligibility.prompt;
        List<String> refImageUrls = buildReferenceImageUrls(item, mode, referenceOverrideUrls);
        ImageTask task = ImageTask.builder()
                .userId(userId)
                .projectId(projectId)
                .prompt(prompt)
                .refImageUrls(refImageUrls.isEmpty() ? null : JSONUtil.toJsonStr(refImageUrls))
                .ratio(MODE_ACTION.equals(mode) ? "1:1" : "16:9")
                .aspectRatio(MODE_ACTION.equals(mode) ? "1:1" : "16:9")
                .width(MODE_ACTION.equals(mode) ? 1536 : 2048)
                .height(MODE_ACTION.equals(mode) ? 1536 : 1152)
                .count(1)
                .category(MODE_ACTION.equals(mode) ? "storyboard_action_grid" : "storyboard_grid25")
                .build();

        try {
            ImageTask completed = imageGenerationConsumer.submitAndWait(task, WAIT_TIMEOUT_MS);
            String imageUrl = imageGenerationService.listItems(completed.getId()).stream()
                    .map(ImageItem::getImageUrl)
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("生成完成但未获取到图片 URL"));
            saveImageUrl(item.getId(), mode, prompt, refImageUrls, imageUrl);
            log.info("[StoryboardGridImage] 宫格图生成成功: itemId={}, mode={}, imageUrl={}",
                    item.getId(), mode, imageUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[StoryboardGridImage] 宫格图生成被中断: itemId={}", item.getId());
        } catch (Exception e) {
            log.warn("[StoryboardGridImage] 宫格图生成失败: itemId={}, mode={}, error={}",
                    item.getId(), mode, e.getMessage(), e);
        }
    }

    private List<StoryboardItem> selectItems(Long storyboardId, List<Long> requestedIds) {
        List<StoryboardItem> allItems = storyboardService.listItems(storyboardId);
        if (CollUtil.isEmpty(requestedIds)) {
            return allItems;
        }
        Set<Long> requestedIdSet = new LinkedHashSet<>(requestedIds);
        return allItems.stream()
                .filter(item -> requestedIdSet.contains(item.getId()))
                .toList();
    }

    private Eligibility checkEligibility(StoryboardItem item, boolean force) {
        String mode = resolveMode(item);
        if (mode == null) {
            return Eligibility.skip("missingMode");
        }
        if (!force && StrUtil.isNotBlank(resolveImageUrl(item, mode))) {
            return Eligibility.skip("generated");
        }
        String prompt = resolvePrompt(item, mode);
        if (StrUtil.isBlank(prompt)) {
            return Eligibility.skip("missingPrompt");
        }
        return Eligibility.ready(mode, prompt);
    }

    private String resolveMode(StoryboardItem item) {
        if (isSupportedMode(item.getVideoWorkflowMode())) {
            return item.getVideoWorkflowMode();
        }
        if (isSupportedMode(item.getVideoWorkflowResolvedMode())) {
            return item.getVideoWorkflowResolvedMode();
        }
        return null;
    }

    private boolean isSupportedMode(String mode) {
        return MODE_ACTION.equals(mode) || MODE_NARRATIVE.equals(mode);
    }

    private String resolveImageUrl(StoryboardItem item, String mode) {
        return MODE_ACTION.equals(mode) ? item.getActionStoryboardImageUrl() : item.getGrid25ImageUrl();
    }

    private String resolvePrompt(StoryboardItem item, String mode) {
        return MODE_ACTION.equals(mode) ? item.getActionStoryboardPrompt() : item.getGrid25Prompt();
    }

    private void saveImageUrl(Long itemId, String mode, String prompt, List<String> refImageUrls, String imageUrl) {
        StoryboardWorkflowUpdateReqVO reqVO = new StoryboardWorkflowUpdateReqVO();
        reqVO.setVideoWorkflowResolvedMode(mode);
        reqVO.setVideoPromptMode(mode);
        if (MODE_ACTION.equals(mode)) {
            reqVO.setActionStoryboardPrompt(prompt);
            reqVO.setActionStoryboardImageUrl(imageUrl);
        } else {
            reqVO.setGrid25Prompt(prompt);
            reqVO.setGrid25ImageUrl(imageUrl);
            reqVO.setGrid25ReferenceImageUrls(refImageUrls.isEmpty() ? null : JSONUtil.toJsonStr(refImageUrls));
        }
        storyboardService.updateItemWorkflow(itemId, reqVO);
    }

    private List<String> buildReferenceImageUrls(StoryboardItem item, String mode, List<String> referenceOverrideUrls) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (referenceOverrideUrls != null) {
            referenceOverrideUrls.forEach(url -> addUrl(urls, url));
        }
        if (MODE_NARRATIVE.equals(mode)) {
            addJsonStringArray(urls, item.getGrid25ReferenceImageUrls());
            addUrl(urls, item.getFirstFrameImageUrl());
            addUrl(urls, item.getLastFrameImageUrl());
        } else {
            addUrl(urls, item.getFirstFrameImageUrl());
        }
        addUrl(urls, item.getStoryboardImageUrl());
        addUrl(urls, item.getGeneratedImageUrl());
        addUrl(urls, item.getImageUrl());
        addUrl(urls, item.getReferenceImageUrl());
        addAssetItemUrls(urls, item.getCharacterIds());
        addAssetItemUrl(urls, item.getSceneAssetItemId());
        addAssetItemUrls(urls, item.getSceneAssetItemIds());
        addAssetItemUrls(urls, item.getPropIds());
        return new ArrayList<>(urls);
    }

    private void addAssetItemUrls(Set<String> urls, String rawIds) {
        for (Long id : parseIds(rawIds)) {
            addAssetItemUrl(urls, id);
        }
    }

    private void addAssetItemUrl(Set<String> urls, Long id) {
        if (id == null) {
            return;
        }
        try {
            AssetItem assetItem = assetService.getItemById(id);
            addUrl(urls, assetItem.getImageUrl());
            addUrl(urls, assetItem.getThumbnailUrl());
        } catch (Exception e) {
            log.warn("[StoryboardGridImage] 读取关联资产失败: assetItemId={}, error={}", id, e.getMessage());
        }
    }

    private List<Long> parseIds(String rawIds) {
        if (StrUtil.isBlank(rawIds)) {
            return List.of();
        }
        String trimmed = rawIds.trim();
        try {
            if (JSONUtil.isTypeJSONArray(trimmed)) {
                return JSONUtil.parseArray(trimmed).stream()
                        .map(this::toLong)
                        .filter(Objects::nonNull)
                        .toList();
            }
        } catch (Exception ignored) {
            // 兼容逗号分隔的旧数据。
        }
        return StrUtil.split(trimmed, ',').stream()
                .map(this::toLong)
                .filter(Objects::nonNull)
                .toList();
    }

    private void addJsonStringArray(Set<String> urls, String rawUrls) {
        if (StrUtil.isBlank(rawUrls)) {
            return;
        }
        try {
            if (JSONUtil.isTypeJSONArray(rawUrls)) {
                JSONUtil.parseArray(rawUrls).stream()
                        .map(String::valueOf)
                        .forEach(url -> addUrl(urls, url));
                return;
            }
        } catch (Exception ignored) {
            // 兼容单 URL 字段。
        }
        addUrl(urls, rawUrls);
    }

    private Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addUrl(Set<String> urls, String url) {
        if (StrUtil.isNotBlank(url)) {
            urls.add(url.trim());
        }
    }

    private record Eligibility(String mode, String prompt, String skipReason) {
        static Eligibility ready(String mode, String prompt) {
            return new Eligibility(mode, prompt, null);
        }

        static Eligibility skip(String reason) {
            return new Eligibility(null, null, reason);
        }
    }

    private static final class Counters {
        private final int totalCount;
        private int submittedCount;
        private int skippedGeneratedCount;
        private int skippedMissingModeCount;
        private int skippedMissingPromptCount;
        private int skippedRunningCount;

        private Counters(int totalCount) {
            this.totalCount = totalCount;
        }

        private void addSkip(String reason) {
            switch (reason) {
                case "generated" -> skippedGeneratedCount++;
                case "missingMode" -> skippedMissingModeCount++;
                case "missingPrompt" -> skippedMissingPromptCount++;
                default -> {
                }
            }
        }

        private StoryboardGridImageGenerateRespVO toResp() {
            String message = submittedCount > 0
                    ? "已提交 " + submittedCount + " 个宫格图生成任务"
                    : "没有可提交的宫格图生成任务";
            return StoryboardGridImageGenerateRespVO.builder()
                    .totalCount(totalCount)
                    .submittedCount(submittedCount)
                    .skippedGeneratedCount(skippedGeneratedCount)
                    .skippedMissingModeCount(skippedMissingModeCount)
                    .skippedMissingPromptCount(skippedMissingPromptCount)
                    .skippedRunningCount(skippedRunningCount)
                    .message(message)
                    .build();
        }
    }
}
