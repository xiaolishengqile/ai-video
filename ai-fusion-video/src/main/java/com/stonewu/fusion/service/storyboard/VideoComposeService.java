package com.stonewu.fusion.service.storyboard;

import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.stonewu.fusion.service.storage.MediaStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 按集合成视频服务。
 * <p>
 * 流程：取该集所有场次的所有镜头视频（按 sortOrder 排序），下载到临时目录，
 * 用 ffmpeg concat 拼接（先尝试 demuxer 零转码；失败则 fallback 到 filter_complex 重新编码），
 * 然后通过 MediaStorageService 持久化，更新 episode 的合成状态与URL。
 */
@Service
@Slf4j
public class VideoComposeService {

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;

    private final StoryboardService storyboardService;
    private final StoryboardEpisodeMapper episodeMapper;
    private final MediaStorageService mediaStorageService;
    private final Executor videoComposeExecutor;

    @Value("${file.media.local.path:/www/wwwroot/aifusionvideo.cc/source/ai-fusion-video/data/media}")
    private String mediaLocalPath;

    @Value("${file.media.public.prefix:/media/}")
    private String mediaPublicPrefix;

    public VideoComposeService(StoryboardService storyboardService,
                               StoryboardEpisodeMapper episodeMapper,
                               MediaStorageService mediaStorageService,
                               @Qualifier("videoComposeExecutor") Executor videoComposeExecutor) {
        this.storyboardService = storyboardService;
        this.episodeMapper = episodeMapper;
        this.mediaStorageService = mediaStorageService;
        this.videoComposeExecutor = videoComposeExecutor;
    }

    /**
     * 提交合成任务。同步标记状态为 RUNNING，异步执行实际合成。
     * 若已在合成中则抛出异常。
     */
    public void submitCompose(Long episodeId) {
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new IllegalArgumentException("分镜集不存在: " + episodeId);
        }
        Integer status = episode.getComposeStatus();
        if (status != null && status == STATUS_RUNNING) {
            throw new IllegalStateException("本集已在合成中，请稍候");
        }

        StoryboardEpisode update = new StoryboardEpisode();
        update.setId(episodeId);
        update.setComposeStatus(STATUS_RUNNING);
        update.setComposeErrorMsg(null);
        episodeMapper.updateById(update);

        videoComposeExecutor.execute(() -> {
            try {
                doCompose(episodeId);
            } catch (Throwable t) {
                log.error("[VideoCompose] 合成失败: episodeId={}", episodeId, t);
                markFailed(episodeId, t.getMessage());
            }
        });
    }

    private void doCompose(Long episodeId) throws Exception {
        log.info("[VideoCompose] 开始合成 episodeId={}", episodeId);
        long startMs = System.currentTimeMillis();

        List<String> videoUrls = collectVideoUrls(episodeId);
        if (videoUrls.isEmpty()) {
            throw new IllegalStateException("本集没有可合成的视频，请先生成镜头视频");
        }
        log.info("[VideoCompose] episodeId={}, 待合成视频数={}", episodeId, videoUrls.size());

        Path workDir = Files.createTempDirectory("compose_ep_" + episodeId + "_");
        try {
            List<Path> localFiles = new ArrayList<>();
            for (int i = 0; i < videoUrls.size(); i++) {
                Path local = workDir.resolve(String.format("v%04d.mp4", i));
                downloadToFile(videoUrls.get(i), local);
                localFiles.add(local);
            }

            Path listFile = workDir.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : localFiles) {
                String s = f.toAbsolutePath().toString().replace("'", "'\\''");
                sb.append("file '").append(s).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            Path output = workDir.resolve("output.mp4");
            boolean ok = runFfmpegConcatDemuxer(listFile, output);
            if (!ok) {
                log.warn("[VideoCompose] concat demuxer 失败，回退到 filter_complex episodeId={}", episodeId);
                ok = runFfmpegFilterConcat(localFiles, output);
            }
            if (!ok || !Files.exists(output) || Files.size(output) == 0) {
                throw new RuntimeException("ffmpeg 合成失败（concat 与 filter 均失败）");
            }

            byte[] data = Files.readAllBytes(output);
            String storedUrl = mediaStorageService.storeBytes(data, "videos/composed", "mp4");
            log.info("[VideoCompose] 已保存到存储: {}", storedUrl);

            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposedVideoUrl(storedUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            episodeMapper.updateById(update);

            log.info("[VideoCompose] 完成 episodeId={}, 耗时={}ms, 视频数={}",
                    episodeId, System.currentTimeMillis() - startMs, videoUrls.size());
        } finally {
            try {
                FileSystemUtils.deleteRecursively(workDir.toFile());
            } catch (Exception e) {
                log.warn("[VideoCompose] 临时目录清理失败 {}", workDir, e);
            }
        }
    }

    private List<String> collectVideoUrls(Long episodeId) {
        List<StoryboardScene> scenes = storyboardService.listScenesByEpisode(episodeId);
        scenes.sort(Comparator.comparing(s -> Optional.ofNullable(s.getSortOrder()).orElse(0)));

        List<String> urls = new ArrayList<>();
        for (StoryboardScene scene : scenes) {
            List<StoryboardItem> items = storyboardService.listItemsByScene(scene.getId());
            items.sort(Comparator.comparing(i -> Optional.ofNullable(i.getSortOrder()).orElse(0)));
            for (StoryboardItem item : items) {
                String url = StringUtils.hasText(item.getVideoUrl())
                        ? item.getVideoUrl()
                        : item.getGeneratedVideoUrl();
                if (StringUtils.hasText(url)) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    private boolean runFfmpegConcatDemuxer(Path listFile, Path output) throws Exception {
        List<String> cmd = List.of(
                "ffmpeg", "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                output.toString()
        );
        return runFfmpeg(cmd, "concat-demuxer", 15);
    }

    private boolean runFfmpegFilterConcat(List<Path> files, Path output) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        for (Path f : files) {
            cmd.add("-i");
            cmd.add(f.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            filter.append("[").append(i).append(":v][").append(i).append(":a]");
        }
        filter.append("concat=n=").append(files.size()).append(":v=1:a=1[outv][outa]");
        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[outv]");
        cmd.add("-map");
        cmd.add("[outa]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add(output.toString());
        return runFfmpeg(cmd, "filter-complex", 30);
    }

    private boolean runFfmpeg(List<String> cmd, String tag, int timeoutMinutes) throws Exception {
        log.info("[VideoCompose:{}] 执行: {}", tag, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[ffmpeg:{}] {}", tag, line);
                }
            }
        }
        boolean done = p.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            log.error("[VideoCompose:{}] 超时，已强制终止", tag);
            return false;
        }
        int exit = p.exitValue();
        if (exit != 0) {
            log.error("[VideoCompose:{}] 退出码非 0: {}", tag, exit);
            return false;
        }
        return true;
    }

    private void downloadToFile(String url, Path dest) throws IOException {
        if (url.startsWith(mediaPublicPrefix)) {
            String rel = url.substring(mediaPublicPrefix.length());
            Path local = Paths.get(mediaLocalPath, rel);
            if (Files.exists(local)) {
                Files.copy(local, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            log.warn("[VideoCompose] /media/ 路径文件不存在，回退 HTTP: {}", url);
        }
        URI uri = URI.create(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载失败 HTTP " + code + ": " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void markFailed(Long episodeId, String msg) {
        try {
            String trimmed = msg == null ? "未知错误" : (msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposeStatus(STATUS_FAILED);
            update.setComposeErrorMsg(trimmed);
            episodeMapper.updateById(update);
        } catch (Exception e) {
            log.error("[VideoCompose] 更新失败状态异常", e);
        }
    }
}
