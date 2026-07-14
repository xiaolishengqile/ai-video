package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.asset.vo.AssetFolderImportResultVO;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.asset.model.AssetFolderImportFile;
import com.stonewu.fusion.service.asset.model.AssetFolderImportPreviewItem;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AssetFolderImportService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Set<String> ASSET_TYPES = Set.of("character", "scene", "prop");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");
    private static final Pattern EPISODE_FOLDER = Pattern.compile("第\\s*([0-9]+|[一二三四五六七八九十]+)\\s*集");

    private final AssetFolderImportNameService nameService;
    private final AssetService assetService;
    private final ProjectService projectService;
    private final MediaStorageService mediaStorageService;

    public List<AssetFolderImportPreviewItem> preview(Long projectId, Long userId, String type,
                                                       List<AssetFolderImportFile> files) {
        validateRequest(projectId, userId, type);
        List<AssetFolderImportPreviewItem> candidates = nameService.preview(files).stream()
                .map(this::withEpisodeScope).toList();
        Set<String> incomingRoots = new HashSet<>();
        for (AssetFolderImportPreviewItem candidate : candidates) {
            if (candidate.reason() == null && "root".equals(candidate.kind())) {
                incomingRoots.add(rootKey(candidate.episodeNumber(), candidate.assetName()));
            }
        }

        List<AssetFolderImportPreviewItem> resolved = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            AssetFolderImportPreviewItem candidate = candidates.get(index);
            if (candidate.reason() != null || !"variant_candidate".equals(candidate.kind())) {
                resolved.add(candidate);
                continue;
            }
            boolean hasParent = incomingRoots.contains(rootKey(candidate.episodeNumber(), candidate.assetName()))
                    || assetService.findByProjectEpisodeTypeAndName(projectId, candidate.episodeNumber(), type,
                    candidate.assetName()) != null;
            if (hasParent) {
                resolved.add(new AssetFolderImportPreviewItem(candidate.relativePath(), candidate.originalName(),
                        candidate.assetName(), candidate.variantName(), candidate.itemType(), "variant",
                        candidate.episodeNumber(), null));
            } else {
                resolved.add(new AssetFolderImportPreviewItem(candidate.relativePath(), candidate.originalName(),
                        nameService.normalizedStem(files.get(index)), null, "initial", "root",
                        candidate.episodeNumber(), null));
            }
        }
        return resolved;
    }

    public AssetFolderImportResultVO importFiles(Long projectId, Long userId, String type,
                                                  List<MultipartFile> files, List<String> relativePaths) {
        validateRequest(projectId, userId, type);
        if (files == null || relativePaths == null || files.size() != relativePaths.size()) {
            throw new BusinessException(400, "files 与 relativePaths 必须一一对应");
        }
        List<AssetFolderImportFile> descriptors = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            descriptors.add(new AssetFolderImportFile(relativePaths.get(i), files.get(i).getOriginalFilename()));
        }
        List<AssetFolderImportPreviewItem> plans = preview(projectId, userId, type, descriptors);
        List<AssetFolderImportResultVO.Item> results = new ArrayList<>(java.util.Collections.nCopies(files.size(), null));

        process(files, plans, results, projectId, userId, type, "root");
        process(files, plans, results, projectId, userId, type, "variant");
        return new AssetFolderImportResultVO(results);
    }

    private void process(List<MultipartFile> files, List<AssetFolderImportPreviewItem> plans,
                         List<AssetFolderImportResultVO.Item> results, Long projectId, Long userId,
                         String type, String kind) {
        for (int i = 0; i < files.size(); i++) {
            AssetFolderImportPreviewItem plan = plans.get(i);
            if (plan.reason() != null) {
                results.set(i, result(plan, "failed", plan.reason()));
                continue;
            }
            if (!kind.equals(plan.kind())) {
                continue;
            }
            try {
                results.set(i, "root".equals(kind)
                        ? importRoot(projectId, userId, type, files.get(i), plan)
                        : importVariant(projectId, userId, type, files.get(i), plan));
            } catch (Exception e) {
                results.set(i, result(plan, "failed", e.getMessage()));
            }
        }
    }

    private AssetFolderImportResultVO.Item importRoot(Long projectId, Long userId, String type,
                                                        MultipartFile file, AssetFolderImportPreviewItem plan) throws IOException {
        if (assetService.findByProjectEpisodeTypeAndName(projectId, plan.episodeNumber(), type, plan.assetName()) != null) {
            return result(plan, "skipped", "同名资产已存在");
        }
        String url = store(file);
        AssetService.FindOrCreateResult assetResult = assetService.findOrCreate(Asset.builder()
                .projectId(projectId).episodeNumber(plan.episodeNumber()).userId(userId)
                .type(type).name(plan.assetName()).sourceType(1).build());
        if (!assetResult.created()) {
            return result(plan, "skipped", "同名资产已存在");
        }
        Asset asset = assetResult.asset();
        AssetItem initial = assetService.listItems(asset.getId()).stream()
                .filter(item -> "initial".equals(item.getItemType())).findFirst()
                .orElseThrow(() -> new BusinessException("初始子资产不存在"));
        assetService.updateItem(AssetItem.builder().id(initial.getId()).assetId(asset.getId())
                .itemType("initial").name(asset.getName()).imageUrl(url).sourceType(1).build());
        return result(plan, "success", null);
    }

    private AssetFolderImportResultVO.Item importVariant(Long projectId, Long userId, String type,
                                                           MultipartFile file, AssetFolderImportPreviewItem plan) throws IOException {
        Asset parent = assetService.findByProjectEpisodeTypeAndName(projectId, plan.episodeNumber(), type, plan.assetName());
        if (parent == null) {
            return result(plan, "failed", "未找到可附加的父资产");
        }
        if (assetService.listItems(parent.getId()).stream().anyMatch(item -> plan.variantName().equals(item.getName()))) {
            return result(plan, "skipped", "同名子资产已存在");
        }
        String url = store(file);
        assetService.createItem(AssetItem.builder().assetId(parent.getId()).itemType(plan.itemType())
                .name(plan.variantName()).imageUrl(url).sourceType(1).build());
        return result(plan, "success", null);
    }

    private String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 100MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("仅支持图片格式：PNG, JPEG, WebP, GIF");
        }
        return mediaStorageService.storeBytes(file.getBytes(), "images", extension(file.getOriginalFilename()));
    }

    private String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private AssetFolderImportResultVO.Item result(AssetFolderImportPreviewItem plan, String status, String reason) {
        return new AssetFolderImportResultVO.Item(plan.relativePath(), plan.originalName(), status,
                plan.assetName(), plan.variantName(), plan.episodeNumber(), reason);
    }

    private AssetFolderImportPreviewItem withEpisodeScope(AssetFolderImportPreviewItem item) {
        Set<Integer> episodeNumbers = new HashSet<>();
        String path = item.relativePath() == null ? "" : item.relativePath().replace('\\', '/');
        for (String segment : path.split("/")) {
            Matcher matcher = EPISODE_FOLDER.matcher(segment);
            while (matcher.find()) {
                Integer number = parseEpisodeNumber(matcher.group(1));
                if (number == null || number < 1 || number > 99) {
                    return new AssetFolderImportPreviewItem(item.relativePath(), item.originalName(), item.assetName(),
                            item.variantName(), item.itemType(), item.kind(), null, "剧集目录格式不合法");
                }
                episodeNumbers.add(number);
            }
        }
        if (episodeNumbers.size() != 1) {
            return new AssetFolderImportPreviewItem(item.relativePath(), item.originalName(), item.assetName(),
                    item.variantName(), item.itemType(), item.kind(), null,
                    episodeNumbers.isEmpty() ? "路径必须包含一个第 N 集目录" : "路径包含多个剧集目录");
        }
        return new AssetFolderImportPreviewItem(item.relativePath(), item.originalName(), item.assetName(),
                item.variantName(), item.itemType(), item.kind(), episodeNumbers.iterator().next(), null);
    }

    private Integer parseEpisodeNumber(String value) {
        if (value.matches("[0-9]+")) return Integer.valueOf(value);
        if ("十".equals(value)) return 10;
        int ten = value.indexOf('十');
        if (ten < 0) return chineseDigit(value);
        int tens = ten == 0 ? 1 : chineseDigit(value.substring(0, ten));
        int ones = ten == value.length() - 1 ? 0 : chineseDigit(value.substring(ten + 1));
        return tens == 0 || ones < 0 ? null : tens * 10 + ones;
    }

    private String rootKey(Integer episodeNumber, String assetName) {
        return episodeNumber + "\\u0000" + assetName;
    }

    private int chineseDigit(String value) {
        return switch (value) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> -1;
        };
    }

    private void validateRequest(Long projectId, Long userId, String type) {
        if (projectId == null || userId == null || type == null || !ASSET_TYPES.contains(type)) {
            throw new BusinessException(400, "projectId、用户和资产类型必须有效");
        }
        if (!projectService.canAccessProject(projectId, userId)) {
            throw new BusinessException(403, "无权访问该项目");
        }
    }
}
