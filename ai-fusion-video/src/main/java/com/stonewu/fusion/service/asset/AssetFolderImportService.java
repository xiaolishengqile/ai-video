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

@Service
@RequiredArgsConstructor
public class AssetFolderImportService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Set<String> ASSET_TYPES = Set.of("character", "scene", "prop");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    private final AssetFolderImportNameService nameService;
    private final AssetService assetService;
    private final ProjectService projectService;
    private final MediaStorageService mediaStorageService;

    public List<AssetFolderImportPreviewItem> preview(Long projectId, Long userId, String type,
                                                       List<AssetFolderImportFile> files) {
        validateRequest(projectId, userId, type);
        List<AssetFolderImportPreviewItem> candidates = nameService.preview(files);
        Set<String> incomingRoots = new HashSet<>();
        for (AssetFolderImportPreviewItem candidate : candidates) {
            if ("root".equals(candidate.kind())) {
                incomingRoots.add(candidate.assetName());
            }
        }

        List<AssetFolderImportPreviewItem> resolved = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            AssetFolderImportPreviewItem candidate = candidates.get(index);
            if (!"variant_candidate".equals(candidate.kind())) {
                resolved.add(candidate);
                continue;
            }
            boolean hasParent = incomingRoots.contains(candidate.assetName())
                    || assetService.findByProjectTypeAndName(projectId, type, candidate.assetName()) != null;
            if (hasParent) {
                resolved.add(new AssetFolderImportPreviewItem(candidate.relativePath(), candidate.originalName(),
                        candidate.assetName(), candidate.variantName(), candidate.itemType(), "variant"));
            } else {
                resolved.add(new AssetFolderImportPreviewItem(candidate.relativePath(), candidate.originalName(),
                        nameService.normalizedStem(files.get(index)), null, "initial", "root"));
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
        if (assetService.findByProjectTypeAndName(projectId, type, plan.assetName()) != null) {
            return result(plan, "skipped", "同名资产已存在");
        }
        String url = store(file);
        Asset asset = assetService.create(Asset.builder()
                .projectId(projectId).userId(userId).type(type).name(plan.assetName()).sourceType(1).build());
        AssetItem initial = assetService.listItems(asset.getId()).stream()
                .filter(item -> "initial".equals(item.getItemType())).findFirst()
                .orElseThrow(() -> new BusinessException("初始子资产不存在"));
        assetService.updateItem(AssetItem.builder().id(initial.getId()).assetId(asset.getId())
                .itemType("initial").name(asset.getName()).imageUrl(url).sourceType(1).build());
        return result(plan, "success", null);
    }

    private AssetFolderImportResultVO.Item importVariant(Long projectId, Long userId, String type,
                                                           MultipartFile file, AssetFolderImportPreviewItem plan) throws IOException {
        Asset parent = assetService.findByProjectTypeAndName(projectId, type, plan.assetName());
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
                plan.assetName(), plan.variantName(), reason);
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
