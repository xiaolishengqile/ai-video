package com.stonewu.fusion.service.asset;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Shared asset-name normalization for prebinding and episode-local search. */
@Component
public class AssetNameNormalizer {

    private static final List<String> VISUAL_SUFFIXES = List.of(
            "完整档案", "整体档案", "角色档案", "场景档案", "道具档案", "六面展开图",
            "整体设定图", "设定图", "表情图", "三视图", "角色图", "场景图", "道具图",
            "参考图", "多机位", "战斗形态", "战斗服", "内景", "外景", "夜景",
            "正面", "侧面", "背面", "细节", "群像", "近景", "远景", "特写", "海报", "档案");

    public NameForms forms(String rawName) {
        String raw = rawName == null ? "" : rawName.trim();
        String leaf = pathLeaf(raw);
        String display = normalize(leaf);
        String base = removeVisualSuffixes(display);
        return new NameForms(raw, display, base, visualAliases(display));
    }

    private String pathLeaf(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase()
                .replaceAll("[\\s\\u00a0\\u3000]+", "")
                .replaceAll("[\\p{P}\\p{S}]", "");
    }

    private String removeVisualSuffixes(String value) {
        String current = value;
        boolean changed;
        do {
            changed = false;
            for (String suffix : VISUAL_SUFFIXES) {
                String normalizedSuffix = normalize(suffix);
                if (current.endsWith(normalizedSuffix) && current.length() > normalizedSuffix.length()) {
                    current = current.substring(0, current.length() - normalizedSuffix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return current;
    }

    private List<String> visualAliases(String display) {
        List<String> aliases = new ArrayList<>();
        String current = display;
        while (true) {
            String shorter = removeOneVisualSuffix(current);
            if (shorter.equals(current)) {
                return List.copyOf(aliases);
            }
            aliases.add(shorter);
            current = shorter;
        }
    }

    private String removeOneVisualSuffix(String value) {
        for (String suffix : VISUAL_SUFFIXES) {
            String normalizedSuffix = normalize(suffix);
            if (value.endsWith(normalizedSuffix) && value.length() > normalizedSuffix.length()) {
                return value.substring(0, value.length() - normalizedSuffix.length());
            }
        }
        return value;
    }

    public record NameForms(String raw, String display, String base, List<String> aliases) {
    }
}
