package com.stonewu.fusion.service.script;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.mapper.script.ScriptAssetBindingMapper;
import com.stonewu.fusion.service.asset.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds deterministic episode-scoped bindings from uploaded assets to script text. */
@Service
@RequiredArgsConstructor
public class ScriptAssetPrebindingService {

    private final ScriptService scriptService;
    private final AssetService assetService;
    private final ScriptAssetBindingMapper bindingMapper;

    @Transactional
    public PrebindingSummary runEpisodePrebinding(Long projectId, Long scriptId, Long scriptEpisodeId) {
        Script script = scriptService.getById(scriptId);
        if (!Objects.equals(script.getProjectId(), projectId)) {
            throw new BusinessException("剧本不属于指定项目");
        }
        ScriptEpisode episode = scriptService.getEpisodeById(scriptEpisodeId);
        if (!Objects.equals(episode.getScriptId(), scriptId)) {
            throw new BusinessException("剧本分集不属于指定剧本");
        }

        bindingMapper.delete(new LambdaQueryWrapper<ScriptAssetBinding>()
                .eq(ScriptAssetBinding::getScriptEpisodeId, scriptEpisodeId));

        String haystack = normalizeText(buildEpisodeText(episode, scriptService.listScenesByEpisode(scriptEpisodeId)));
        int matched = 0;
        int uploadedUnused = 0;
        List<ScriptAssetBinding> rows = new ArrayList<>();
        for (Asset asset : assetService.listByProjectEpisode(projectId, episode.getEpisodeNumber(), null)) {
            AssetItem item = preferredItem(asset.getId());
            Match match = matchAsset(asset.getName(), haystack);
            if (match.matched()) matched++;
            else uploadedUnused++;
            rows.add(ScriptAssetBinding.builder()
                    .projectId(projectId)
                    .scriptId(scriptId)
                    .scriptEpisodeId(scriptEpisodeId)
                    .episodeNumber(episode.getEpisodeNumber())
                    .assetType(asset.getType())
                    .entityName(match.entityName())
                    .assetId(asset.getId())
                    .assetItemId(item == null ? null : item.getId())
                    .matchStatus(match.matched() ? "matched" : "uploaded_unused")
                    .matchSource(match.source())
                    .confidence(match.matched() ? 100 : 0)
                    .evidenceText(match.matched() ? match.entityName() : null)
                    .reviewed(match.matched())
                    .build());
        }
        rows.forEach(bindingMapper::insert);
        return new PrebindingSummary(matched, 0, 0, 0, uploadedUnused);
    }

    public List<ScriptAssetBinding> listBindings(Long scriptEpisodeId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<ScriptAssetBinding>()
                .eq(ScriptAssetBinding::getScriptEpisodeId, scriptEpisodeId)
                .orderByAsc(ScriptAssetBinding::getAssetType)
                .orderByAsc(ScriptAssetBinding::getId));
    }

    public ScriptAssetBinding getBinding(Long id) {
        return bindingMapper.selectById(id);
    }

    @Transactional
    public ScriptAssetBinding reviewBinding(Long id, Long assetId, Long assetItemId, String matchStatus,
                                            String matchSource, Boolean reviewed) {
        ScriptAssetBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException("资产匹配记录不存在: " + id);
        }
        if (assetId != null) binding.setAssetId(assetId);
        if (assetItemId != null) binding.setAssetItemId(assetItemId);
        if (matchStatus != null && !matchStatus.isBlank()) binding.setMatchStatus(matchStatus);
        if (matchSource != null && !matchSource.isBlank()) binding.setMatchSource(matchSource);
        if (reviewed != null) binding.setReviewed(reviewed);
        bindingMapper.updateById(binding);
        return binding;
    }

    private String buildEpisodeText(ScriptEpisode episode, List<ScriptSceneItem> scenes) {
        StringBuilder builder = new StringBuilder();
        append(builder, episode.getTitle());
        append(builder, episode.getSynopsis());
        append(builder, episode.getRawContent());
        for (ScriptSceneItem scene : scenes) {
            append(builder, scene.getSceneHeading());
            append(builder, scene.getLocation());
            append(builder, scene.getCharacters());
            append(builder, scene.getSceneDescription());
            append(builder, scene.getDialogues());
        }
        return builder.toString();
    }

    private void append(StringBuilder builder, String text) {
        if (text != null && !text.isBlank()) {
            builder.append('\n').append(text);
        }
    }

    private AssetItem preferredItem(Long assetId) {
        List<AssetItem> items = assetService.listItems(assetId);
        if (items.isEmpty()) return null;
        return items.stream()
                .filter(item -> item.getImageUrl() != null && !item.getImageUrl().isBlank())
                .findFirst()
                .orElse(items.getFirst());
    }

    private Match matchAsset(String rawName, String haystack) {
        String exact = safe(rawName);
        if (!exact.isBlank() && haystack.contains(normalizeText(exact))) {
            return new Match(true, exact, "exact_name");
        }
        String display = displayName(exact);
        if (!display.isBlank() && !display.equals(exact) && containsDisplayEvidence(haystack, display)) {
            return new Match(true, display, "display_name");
        }
        String clean = cleanName(display.isBlank() ? exact : display);
        if (!clean.isBlank() && !clean.equals(display) && haystack.contains(normalizeText(clean))) {
            return new Match(true, clean, "clean_name");
        }
        return new Match(false, display.isBlank() ? exact : display, "none");
    }

    private static String displayName(String name) {
        String[] parts = name.split("[/|｜]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isBlank()) return part;
        }
        return name.trim();
    }

    private static String cleanName(String name) {
        return name.replaceAll("[（(].*?[）)]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static boolean containsDisplayEvidence(String haystack, String display) {
        String normalized = normalizeText(display);
        if (haystack.contains(normalized)) {
            return true;
        }
        if (normalized.contains("的")) {
            String[] parts = normalized.split("的");
            int meaningful = 0;
            for (String part : parts) {
                if (part.length() >= 2) {
                    meaningful++;
                    if (!haystack.contains(part)) return false;
                }
            }
            return meaningful >= 2;
        }
        return false;
    }

    private static String normalizeText(String text) {
        return safe(text).replaceAll("\\s+", "");
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private record Match(boolean matched, String entityName, String source) {
    }

    public record PrebindingSummary(int matched, int suggested, int ambiguous, int unmatched, int uploadedUnused) {
    }
}
