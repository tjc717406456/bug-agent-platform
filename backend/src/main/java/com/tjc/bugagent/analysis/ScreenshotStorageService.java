package com.tjc.bugagent.analysis;

import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 保存 Bug 分析截图，并返回服务器路径。
 */
@Service
public class ScreenshotStorageService {
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMAGE_COUNT = 3;
    private final AppProperties appProperties;

    public ScreenshotStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 保存可选截图。
     */
    public String saveScreenshots(Long projectId, MultipartFile[] screenshots) throws IOException {
        if (screenshots == null || screenshots.length == 0) {
            return null;
        }
        if (screenshots.length > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("截图最多上传 " + MAX_IMAGE_COUNT + " 张");
        }
        Path directory = new File(appProperties.getWorkspaceRoot(), "screenshots" + File.separator + "project-" + projectId).toPath()
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(directory);
        List<String> paths = new ArrayList<String>();
        for (MultipartFile screenshot : screenshots) {
            if (screenshot == null || screenshot.isEmpty()) {
                continue;
            }
            validateImage(screenshot);
            String extension = resolveExtension(screenshot.getOriginalFilename(), screenshot.getContentType());
            Path target = directory.resolve(UUID.randomUUID().toString() + extension).normalize();
            if (!target.startsWith(directory)) {
                throw new IllegalStateException("invalid screenshot path");
            }
            screenshot.transferTo(target.toFile());
            paths.add(target.toString());
        }
        return paths.isEmpty() ? null : paths.stream().collect(Collectors.joining("\n"));
    }

    private void validateImage(MultipartFile screenshot) {
        if (screenshot.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("单张截图不能超过 5MB");
        }
        String contentType = screenshot.getContentType();
        if (contentType == null || !isAllowedContentType(contentType)) {
            throw new IllegalArgumentException("只支持 png、jpg、jpeg、webp 图片");
        }
    }

    private boolean isAllowedContentType(String contentType) {
        String value = contentType.toLowerCase(Locale.ROOT);
        return "image/png".equals(value)
                || "image/jpeg".equals(value)
                || "image/jpg".equals(value)
                || "image/webp".equals(value);
    }

    private String resolveExtension(String originalFilename, String contentType) {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return ".png";
        }
        if (name.endsWith(".jpg")) {
            return ".jpg";
        }
        if (name.endsWith(".jpeg")) {
            return ".jpeg";
        }
        if (name.endsWith(".webp")) {
            return ".webp";
        }
        return "image/png".equalsIgnoreCase(contentType) ? ".png" : ".jpg";
    }
}
