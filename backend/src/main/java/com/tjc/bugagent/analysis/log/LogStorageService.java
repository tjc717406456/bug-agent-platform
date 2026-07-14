package com.tjc.bugagent.analysis.log;

import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 保存上传的日志文件并读出文本；每天 0 点清理过期日志。
 */
@Service
public class LogStorageService {
    private static final Logger log = LoggerFactory.getLogger(LogStorageService.class);

    private final AppProperties appProperties;

    public LogStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 创建日志目录和上传临时目录，确保容器内的临时文件与最终日志位于同一 workspace 卷。
     */
    @PostConstruct
    public void initializeDirectories() throws IOException {
        Files.createDirectories(Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize());
        Files.createDirectories(Paths.get(appProperties.getLog().getUploadTempDir()).toAbsolutePath().normalize());
    }

    /**
     * 保存日志文件（超过大小上限直接拒绝），返回 logId（文件名），分析时凭它再读。
     */
    public String save(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("日志文件为空");
        }
        long maxBytes = appProperties.getLog().getMaxBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("日志文件超过大小上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        Path dir = Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String logId = UUID.randomUUID() + ".log";
        Path target = dir.resolve(logId);
        long startedAt = System.currentTimeMillis();
        log.info("开始保存上传日志 fileName={} size={} target={}", file.getOriginalFilename(), file.getSize(), target);
        file.transferTo(target.toFile());
        log.info("上传日志保存完成 logId={} size={} elapsedMs={}", logId, file.getSize(),
                System.currentTimeMillis() - startedAt);
        return logId;
    }

    /**
     * 按 logId 读出保存的日志文本，分析时调用。文件不存在或非法 logId 返回 null。
     */
    public String read(String logId) {
        if (logId == null || logId.trim().isEmpty()) {
            return null;
        }
        // 防目录穿越：logId 只能是纯文件名
        if (logId.contains("/") || logId.contains("\\") || logId.contains("..")) {
            return null;
        }
        Path path = Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize().resolve(logId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("read log file failed: {}", logId, exception);
            return null;
        }
    }

    /**
     * 解析 logId 对应的日志文件路径（不读取内容），非法/不存在返回 null。
     * 给 search_log 流式 grep 用，避免把大日志全文读进内存。
     */
    public String resolvePath(String logId) {
        if (logId == null || logId.trim().isEmpty()) {
            return null;
        }
        // 防目录穿越：logId 只能是纯文件名
        if (logId.contains("/") || logId.contains("\\") || logId.contains("..")) {
            return null;
        }
        Path path = Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize().resolve(logId);
        return Files.exists(path) ? path.toString() : null;
    }

    /**
     * 每天 0 点清理：删除保留期之外的日志文件，避免磁盘堆积。
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanup() {
        File dir = Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize().toFile();
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (long) appProperties.getLog().getRetentionDays() * 24 * 60 * 60 * 1000;
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff && file.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("已清理过期日志文件 {} 个", deleted);
        }
    }
}
