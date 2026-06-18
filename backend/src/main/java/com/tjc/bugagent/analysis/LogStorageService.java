package com.tjc.bugagent.analysis;

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
     * 保存日志文件（超过大小上限直接拒绝），返回读出的文本。
     */
    public String saveAndRead(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("日志文件为空");
        }
        long maxBytes = appProperties.getLog().getMaxBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("日志文件超过大小上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        Path dir = Paths.get(appProperties.getLog().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + ".log");
        file.transferTo(target.toFile());
        return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
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
