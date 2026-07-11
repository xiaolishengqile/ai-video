package com.stonewu.fusion.service.storyboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分镜 Excel 导出服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardExcelExportService {

    private static final int IMAGE_PREVIEW_COLUMN = 3;
    private static final int IMAGE_PREVIEW_ROW_HEIGHT = 92;
    private static final int IMAGE_PREVIEW_COLUMN_WIDTH = 18;
    private static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;

    private final StoryboardService storyboardService;
    private final OkHttpClient imageHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${app.storage.local-base-path:./data/media}")
    private String localBasePath;

    /**
     * 导出分镜 Excel。
     *
     * @param storyboardId 分镜ID
     * @param episodeId    分镜集ID，可为空
     * @param sceneId      分镜场次ID，可为空
     * @return Excel 文件字节
     */
    public byte[] export(Long storyboardId, Long episodeId, Long sceneId) {
        Storyboard storyboard = storyboardService.getById(storyboardId);
        List<StoryboardItem> items = resolveItems(storyboardId, episodeId, sceneId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StoryboardItem item : items) {
            rows.add(toRow(storyboard, item));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            writer.renameSheet("分镜表");
            writer.write(rows, true);
            writeImageAssetSheet(writer, items);
            writer.flush(out, true);
            return out.toByteArray();
        }
    }

    private List<StoryboardItem> resolveItems(Long storyboardId, Long episodeId, Long sceneId) {
        if (sceneId != null) {
            return storyboardService.listItemsByScene(sceneId);
        }
        if (episodeId != null) {
            List<StoryboardItem> items = new ArrayList<>();
            for (StoryboardScene scene : storyboardService.listScenesByEpisode(episodeId)) {
                items.addAll(storyboardService.listItemsByScene(scene.getId()));
            }
            return items;
        }
        return storyboardService.listItems(storyboardId);
    }

    private Map<String, Object> toRow(Storyboard storyboard, StoryboardItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("项目ID", storyboard.getProjectId());
        row.put("分镜ID", storyboard.getId());
        row.put("分镜名称", storyboard.getTitle());
        row.put("分镜集ID", item.getStoryboardEpisodeId());
        row.put("场次ID", item.getStoryboardSceneId());
        row.put("镜号", firstNonBlank(item.getShotNumber(), item.getAutoShotNumber()));
        row.put("模式", item.getVideoWorkflowMode());
        row.put("实际模式", item.getVideoWorkflowResolvedMode());
        row.put("模式判断原因", item.getVideoWorkflowReason());
        row.put("景别", item.getShotType());
        row.put("时长", item.getDuration());
        row.put("摄像机角度", item.getCameraAngle());
        row.put("运镜", item.getCameraMovement());
        row.put("分镜内容", item.getContent());
        row.put("对白", item.getDialogue());
        row.put("声音", item.getSound());
        row.put("关联角色", item.getCharacterIds());
        row.put("关联场景", item.getSceneAssetItemId());
        row.put("关联道具", item.getPropIds());
        row.put("故事板图链接", item.getStoryboardImageUrl());
        row.put("25宫格图链接", item.getGrid25ImageUrl());
        row.put("动作故事板图链接", item.getActionStoryboardImageUrl());
        row.put("关键帧链接", item.getKeyFrameImageUrls());
        row.put("首帧链接", item.getFirstFrameImageUrl());
        row.put("尾帧链接", item.getLastFrameImageUrl());
        row.put("视频提示词", item.getVideoPrompt());
        row.put("视频链接", firstNonBlank(item.getGeneratedVideoUrl(), item.getVideoUrl()));
        row.put("质检结果", item.getQualityCheckResult());
        row.put("备注", item.getRemark());
        return row;
    }

    private void writeImageAssetSheet(ExcelWriter writer, List<StoryboardItem> items) {
        List<ImageAssetRow> imageRows = new ArrayList<>();
        for (StoryboardItem item : items) {
            for (ExportImageAsset asset : collectImageAssets(item)) {
                imageRows.add(new ImageAssetRow(
                        firstNonBlank(item.getShotNumber(), item.getAutoShotNumber(), String.valueOf(item.getId())),
                        asset.label(),
                        asset.url(),
                        asset
                ));
            }
        }

        writer.setSheet("图片资产");
        writer.setColumnWidth(0, 12);
        writer.setColumnWidth(1, 18);
        writer.setColumnWidth(2, 72);
        writer.setColumnWidth(IMAGE_PREVIEW_COLUMN, IMAGE_PREVIEW_COLUMN_WIDTH);

        List<Map<String, Object>> rows = imageRows.stream()
                .map(row -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("镜号", row.shotNumber());
                    data.put("图片类型", row.label());
                    data.put("图片链接", row.url());
                    data.put("图片预览", "");
                    return data;
                })
                .toList();
        writer.write(rows, true);

        for (int i = 0; i < imageRows.size(); i++) {
            int rowIndex = i + 1;
            writer.setRowHeight(rowIndex, IMAGE_PREVIEW_ROW_HEIGHT);
            embedImage(writer, imageRows.get(i).asset(), rowIndex);
        }
        writer.setSheet("分镜表");
    }

    private void embedImage(ExcelWriter writer, ExportImageAsset asset, int rowIndex) {
        try {
            Optional<ExportImageData> imageData = loadImageData(asset.url());
            if (imageData.isEmpty()) {
                return;
            }
            writer.writeImg(
                    imageData.get().data(),
                    imageData.get().pictureType(),
                    0,
                    0,
                    0,
                    0,
                    IMAGE_PREVIEW_COLUMN,
                    rowIndex,
                    IMAGE_PREVIEW_COLUMN + 1,
                    rowIndex + 1
            );
        } catch (Exception e) {
            log.warn("[StoryboardExcelExport] 图片嵌入失败，将仅保留链接: label={}, url={}, error={}",
                    asset.label(), asset.url(), e.getMessage());
        }
    }

    private Optional<ExportImageData> loadImageData(String url) throws IOException {
        if (StrUtil.isBlank(url)) {
            return Optional.empty();
        }
        if (url.startsWith("data:image/")) {
            return decodeDataImage(url);
        }
        if (url.startsWith("/media/")) {
            return readLocalMedia(url);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return downloadRemoteImage(url);
        }
        return Optional.empty();
    }

    private Optional<ExportImageData> decodeDataImage(String url) {
        int splitIndex = url.indexOf(',');
        if (splitIndex < 0) {
            return Optional.empty();
        }
        String meta = url.substring(0, splitIndex);
        String payload = url.substring(splitIndex + 1);
        byte[] data = Base64.getDecoder().decode(payload);
        if (data.length > MAX_IMAGE_BYTES) {
            return Optional.empty();
        }
        return Optional.of(new ExportImageData(data, pictureType(meta)));
    }

    private Optional<ExportImageData> readLocalMedia(String url) throws IOException {
        Path base = Path.of(localBasePath).toAbsolutePath().normalize();
        Path target = base.resolve(url.substring("/media/".length())).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target) || Files.size(target) > MAX_IMAGE_BYTES) {
            return Optional.empty();
        }
        return Optional.of(new ExportImageData(Files.readAllBytes(target), pictureType(url)));
    }

    private Optional<ExportImageData> downloadRemoteImage(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = imageHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return Optional.empty();
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_IMAGE_BYTES) {
                return Optional.empty();
            }
            byte[] data = body.bytes();
            if (data.length > MAX_IMAGE_BYTES) {
                return Optional.empty();
            }
            String contentType = response.header("Content-Type", "");
            return Optional.of(new ExportImageData(data, pictureType(firstNonBlank(contentType, url))));
        }
    }

    static List<ExportImageAsset> collectImageAssets(StoryboardItem item) {
        List<ExportImageAsset> assets = new ArrayList<>();
        addImageAsset(assets, "故事板图", item.getStoryboardImageUrl());
        addImageAsset(assets, "25宫格图", item.getGrid25ImageUrl());
        addImageAsset(assets, "动作故事板图", item.getActionStoryboardImageUrl());
        addImageAsset(assets, "首帧", item.getFirstFrameImageUrl());
        addImageAsset(assets, "尾帧", item.getLastFrameImageUrl());

        List<String> keyFrameUrls = parseStringArray(item.getKeyFrameImageUrls());
        for (int i = 0; i < keyFrameUrls.size(); i++) {
            addImageAsset(assets, "关键帧" + (i + 1), keyFrameUrls.get(i));
        }
        return assets;
    }

    private static void addImageAsset(List<ExportImageAsset> assets, String label, String url) {
        if (StrUtil.isNotBlank(url)) {
            assets.add(new ExportImageAsset(label, url));
        }
    }

    private static List<String> parseStringArray(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(raw);
            List<String> values = new ArrayList<>();
            for (Object item : array) {
                if (item instanceof String value && StrUtil.isNotBlank(value)) {
                    values.add(value);
                }
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private int pictureType(String source) {
        String normalized = StrUtil.nullToEmpty(source).toLowerCase();
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        return Workbook.PICTURE_TYPE_PNG;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ExportImageAsset(String label, String url) {
    }

    private record ImageAssetRow(String shotNumber, String label, String url, ExportImageAsset asset) {
    }

    private record ExportImageData(byte[] data, int pictureType) {
    }
}
