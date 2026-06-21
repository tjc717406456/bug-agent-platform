package com.tjc.bugagent.analysis.log;

import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
        file.transferTo(dir.resolve(logId).toFile());
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
