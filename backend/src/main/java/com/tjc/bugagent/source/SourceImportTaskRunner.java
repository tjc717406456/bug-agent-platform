package com.tjc.bugagent.source;

import com.tjc.bugagent.codegraph.CodeGraphIndexService;
import com.tjc.bugagent.codegraph.CodeGraphRepository;
import com.tjc.bugagent.project.ProjectVersion;
import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 源码导入后台任务：解压、识别源码根目录、构建索引，成功后再清理旧版本。
 */
@Service
public class SourceImportTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(SourceImportTaskRunner.class);
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ENTRIES = 100000;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;

    private final ProjectVersionMapper projectVersionMapper;
    private final CodeGraphRepository codeGraphRepository;
    private final CodeGraphIndexService codeGraphIndexService;

    public SourceImportTaskRunner(ProjectVersionMapper projectVersionMapper,
                                  CodeGraphRepository codeGraphRepository,
                                  CodeGraphIndexService codeGraphIndexService) {
        this.projectVersionMapper = projectVersionMapper;
        this.codeGraphRepository = codeGraphRepository;
        this.codeGraphIndexService = codeGraphIndexService;
    }

    /**
     * 后台执行 ZIP 解压与索引，HTTP 请求只负责把压缩包落盘并提交到这里。
     */
    @Async("sourceImportExecutor")
    public void runZipImport(Long projectId, Long versionId, Path zipPath, Path extractDir) {
        long startedAt = System.currentTimeMillis();
        try {
            projectVersionMapper.updateMessage(versionId, "正在解压 ZIP");
            ExtractionResult extraction = unzip(zipPath, extractDir, versionId);
            if (projectVersionMapper.findById(versionId) == null) {
                log.info("ZIP 导入任务已取消，项目或版本已删除 projectId={} versionId={}", projectId, versionId);
                return;
            }
            Path sourceRoot = findSourceRoot(extractDir);
            projectVersionMapper.updateSourcePath(versionId, sourceRoot.toAbsolutePath().normalize().toString());
            projectVersionMapper.updateMessage(versionId, "解压完成，共 " + extraction.entries + " 个文件，准备建立索引");
            log.info("ZIP 解压完成 projectId={} versionId={} entries={} bytes={} elapsedMs={}", projectId,
                    versionId, extraction.entries, extraction.bytes, System.currentTimeMillis() - startedAt);
            if (codeGraphIndexService.index(projectId, versionId, sourceRoot)) {
                pruneOldVersions(projectId, versionId);
                log.info("ZIP 源码导入完成 projectId={} versionId={} elapsedMs={}", projectId, versionId,
                        System.currentTimeMillis() - startedAt);
            }
        } catch (Exception exception) {
            projectVersionMapper.markFailed(versionId, trimMessage(exception));
            FileUtils.deleteQuietly(extractDir.toFile());
            log.error("ZIP 源码导入失败 projectId={} versionId={}", projectId, versionId, exception);
        } finally {
            FileUtils.deleteQuietly(zipPath.toFile());
            // 项目或版本在后台任务期间被删除时，不能让异步线程把项目目录残留下来。
            if (projectVersionMapper.findById(versionId) == null) {
                codeGraphRepository.clearVersion(projectId, versionId);
                FileUtils.deleteQuietly(importRoot(extractDir).toFile());
            }
        }
    }

    private Path importRoot(Path extractDir) {
        Path parent = extractDir.toAbsolutePath().normalize().getParent();
        return parent == null ? extractDir : parent;
    }

    private ExtractionResult unzip(Path zipPath, Path target, Long versionId) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        int entries = 0;
        long bytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream fileInput = new BufferedInputStream(Files.newInputStream(zipPath));
             ZipInputStream zipInput = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("ZIP 文件数量超过上限 " + MAX_ENTRIES);
                }
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IllegalArgumentException("ZIP 包含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                    continue;
                }
                Files.createDirectories(entryPath.getParent());
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(entryPath))) {
                    int read;
                    while ((read = zipInput.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        bytes += read;
                        if (bytes > MAX_EXTRACTED_BYTES) {
                            throw new IllegalArgumentException("ZIP 解压后大小超过 2GB 上限");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (entries % 500 == 0) {
                    projectVersionMapper.updateMessage(versionId, "正在解压 ZIP，已处理 " + entries + " 个文件");
                }
            }
        }
        return new ExtractionResult(entries, bytes);
    }

    private Path findSourceRoot(Path target) throws IOException {
        if (isSourceRoot(target)) {
            return target;
        }
        try (Stream<Path> children = Files.list(target)) {
            return children.filter(Files::isDirectory)
                    .filter(this::isSourceRoot)
                    .findFirst()
                    .orElse(target);
        }
    }

    private boolean isSourceRoot(Path path) {
        return Files.exists(path.resolve("pom.xml"))
                || Files.exists(path.resolve("build.gradle"))
                || Files.exists(path.resolve("src"));
    }

    private void pruneOldVersions(Long projectId, Long currentVersionId) {
        List<ProjectVersion> versions = projectVersionMapper.listByProject(projectId);
        boolean newerSuccessExists = versions.stream()
                .anyMatch(version -> version.getId() != null && version.getId() > currentVersionId
                        && "SUCCESS".equals(version.getIndexStatus()));
        if (newerSuccessExists) {
            ProjectVersion current = versions.stream()
                    .filter(version -> currentVersionId.equals(version.getId()))
                    .findFirst()
                    .orElse(null);
            codeGraphRepository.clearVersion(projectId, currentVersionId);
            projectVersionMapper.deleteByIdAndProject(currentVersionId, projectId);
            if (current != null) {
                deleteSourceDir(projectId, current.getSourcePath());
            }
            return;
        }
        for (ProjectVersion version : versions) {
            // 同一项目可能连续提交多个导入任务，早完成的任务不能删除编号更大的新任务。
            if (version.getId() == null || version.getId() >= currentVersionId) {
                continue;
            }
            // 较新的任务先完成时，不能删除仍在解压或索引的旧任务目录；旧任务结束后会自行识别更新版本并清理。
            if ("PENDING".equals(version.getIndexStatus()) || "INDEXING".equals(version.getIndexStatus())) {
                continue;
            }
            codeGraphRepository.clearVersion(projectId, version.getId());
            projectVersionMapper.deleteByIdAndProject(version.getId(), projectId);
            deleteSourceDir(projectId, version.getSourcePath());
        }
    }

    private void deleteSourceDir(Long projectId, String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return;
        }
        String projectDirName = "project-" + projectId;
        java.io.File current = new java.io.File(sourcePath);
        while (current != null && current.getParentFile() != null
                && !projectDirName.equals(current.getParentFile().getName())) {
            current = current.getParentFile();
        }
        FileUtils.deleteQuietly(current == null ? new java.io.File(sourcePath) : current);
    }

    private String trimMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 3000 ? message.substring(0, 3000) : message;
    }

    private static final class ExtractionResult {
        private final int entries;
        private final long bytes;

        private ExtractionResult(int entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }
    }
}
