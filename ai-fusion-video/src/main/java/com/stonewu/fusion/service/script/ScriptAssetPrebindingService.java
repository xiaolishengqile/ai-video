package com.stonewu.fusion.service.script;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.mapper.script.ScriptAssetBindingMapper;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.script.model.SceneEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Builds deterministic episode-scoped bindings from uploaded assets to script text. */
@Service
@RequiredArgsConstructor
public class ScriptAssetPrebindingService {

    private static final List<String> VISUAL_NAME_SUFFIXES = List.of(
            "完整档案", "整体档案", "角色档案", "场景档案", "道具档案", "设定图", "三视图",
            "表情图", "角色图", "场景图", "道具图", "参考图", "整体案", "完整案", "群像",
            "近景", "远景", "特写", "海报", "档案");

    // ponytail: in-process per-episode lock; move to DB/distributed lock only if multi-node writes contend.
    private static final ConcurrentMap<Long, Object> EPISODE_LOCKS = new ConcurrentHashMap<>();

    private final ScriptService scriptService;
    private final AssetService assetService;
    private final ScriptAssetBindingMapper bindingMapper;

    @Transactional
    public PrebindingSummary runEpisodePrebinding(Long projectId, Long scriptId, Long scriptEpisodeId) {
        synchronized (EPISODE_LOCKS.computeIfAbsent(scriptEpisodeId, ignored -> new Object())) {
            return runEpisodePrebindingLocked(projectId, scriptId, scriptEpisodeId);
        }
    }

    private PrebindingSummary runEpisodePrebindingLocked(Long projectId, Long scriptId, Long scriptEpisodeId) {
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
    public List<ScriptAssetBinding> recordMissingAssetRequirements(Long projectId, Long scriptId, Long scriptEpisodeId,
                                                                   Integer episodeNumber, Long scriptSceneItemId,
                                                                   String sceneNumber, List<SceneEntity> missingEntities) {
        List<ScriptAssetBinding> saved = new ArrayList<>();
        for (SceneEntity entity : missingEntities) {
            ScriptAssetBinding row = bindingMapper.selectList(new LambdaQueryWrapper<ScriptAssetBinding>()
                            .eq(ScriptAssetBinding::getScriptEpisodeId, scriptEpisodeId)
                            .eq(ScriptAssetBinding::getScriptSceneItemId, scriptSceneItemId)
                            .eq(ScriptAssetBinding::getEntityKey, entity.key())
                            .eq(ScriptAssetBinding::getMatchStatus, "missing_asset")
                            .orderByDesc(ScriptAssetBinding::getId))
                    .stream().findFirst().orElseGet(ScriptAssetBinding::new);
            row.setProjectId(projectId);
            row.setScriptId(scriptId);
            row.setScriptEpisodeId(scriptEpisodeId);
            row.setEpisodeNumber(episodeNumber);
            row.setScriptSceneItemId(scriptSceneItemId);
            row.setAssetType(entity.assetType());
            row.setEntityName(entity.name());
            row.setEntityKey(entity.key());
            row.setAssetId(null);
            row.setAssetItemId(null);
            row.setMatchStatus("missing_asset");
            row.setMatchSource("storyboard_blocked");
            row.setConfidence(0);
            row.setEvidenceText("场次 " + safe(sceneNumber) + " 核心实体缺少可用图片资产");
            row.setCandidateJson(JSONUtil.createObj()
                    .set("entitySubtype", entity.entitySubtype())
                    .set("importance", entity.importance())
                    .set("suggestedLocation", suggestedLocation(episodeNumber, entity.assetType()))
                    .set("suggestedAssetName", suggestedAssetName(entity))
                    .toString());
            row.setReviewed(false);
            if (row.getId() == null) {
                bindingMapper.insert(row);
            } else {
                bindingMapper.updateById(row);
            }
            saved.add(row);
        }
        return saved;
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
        for (String alias : visualAliases(clean.isBlank() ? display : clean)) {
            if (!alias.isBlank() && containsDisplayEvidence(haystack, alias)) {
                return new Match(true, alias, "visual_alias");
            }
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

    private static List<String> visualAliases(String name) {
        List<String> aliases = new ArrayList<>();
        String current = cleanName(name);
        boolean changed;
        do {
            changed = false;
            for (String suffix : VISUAL_NAME_SUFFIXES) {
                if (current.endsWith(suffix) && current.length() > suffix.length() + 1) {
                    current = current.substring(0, current.length() - suffix.length());
                    aliases.add(current);
                    changed = true;
                }
            }
        } while (changed);
        return aliases;
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

    private static String suggestedLocation(Integer episodeNumber, String assetType) {
        return "项目资产 > 第" + episodeNumber + "集 > " + switch (assetType) {
            case "character" -> "角色";
            case "scene" -> "场景";
            case "prop" -> "道具";
            default -> "资产";
        };
    }

    private static String suggestedAssetName(SceneEntity entity) {
        String suffix = switch (entity.assetType()) {
            case "character" -> "mecha".equals(entity.entitySubtype()) ? "机甲本体完整档案" : "完整档案";
            case "scene" -> "场景设定图";
            case "prop" -> "道具设定图";
            default -> "完整档案";
        };
        return entity.name() + "｜" + suffix;
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private record Match(boolean matched, String entityName, String source) {
    }

    public record PrebindingSummary(int matched, int suggested, int ambiguous, int unmatched, int uploadedUnused) {
    }
}
