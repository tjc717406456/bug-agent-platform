package com.tjc.bugagent.source;

import com.tjc.bugagent.codegraph.CodeGraphIndexService;
import com.tjc.bugagent.codegraph.CodeGraphRepository;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectVersion;
import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Imports project source and starts indexing.
 */
@Service
public class SourceImportService {
    private static final Logger log = LoggerFactory.getLogger(SourceImportService.class);
    private final AppProperties appProperties;
    private final ProjectVersionMapper projectVersionMapper;
    private final CodeGraphRepository codeGraphRepository;
    private final CodeGraphIndexService codeGraphIndexService;
    private final SourceImportTaskRunner sourceImportTaskRunner;

    public SourceImportService(AppProperties appProperties, ProjectVersionMapper projectVersionMapper,
                               CodeGraphRepository codeGraphRepository, CodeGraphIndexService codeGraphIndexService,
                               SourceImportTaskRunner sourceImportTaskRunner) {
        this.appProperties = appProperties;
        this.projectVersionMapper = projectVersionMapper;
        this.codeGraphRepository = codeGraphRepository;
        this.codeGraphIndexService = codeGraphIndexService;
        this.sourceImportTaskRunner = sourceImportTaskRunner;
    }

    public Long importGit(Long projectId, GitImportRequest request) throws IOException, InterruptedException {
        Path target = workspacePath(projectId, "git");
        FileUtils.forceMkdir(target.toFile());
        ProcessBuilder builder = new ProcessBuilder(buildGitCommand(request));
        builder.directory(target.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git clone failed, exitCode=" + exitCode);
        }
        File[] files = target.toFile().listFiles(File::isDirectory);
        if (files == null || files.length == 0) {
            throw new IllegalStateException("git clone produced no source directory");
        }
        // 新源码已就绪，清掉该项目的旧版本（图谱 + 磁盘），保证一个项目只留当前一份
        pruneProjectVersions(projectId);
        Long versionId = createVersion(projectId, "GIT", request.getBranchName(), files[0].getAbsolutePath());
        codeGraphIndexService.indexAsync(projectId, versionId, files[0].toPath());
        return versionId;
    }

    public Long importZip(Long projectId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP 文件为空");
        }
        Path importRoot = workspacePath(projectId, "zip");
        Path zipPath = importRoot.resolve("source.zip");
        long startedAt = System.currentTimeMillis();
        log.info("开始接收 ZIP 源码 projectId={} fileName={} size={} target={}", projectId,
                file.getOriginalFilename(), file.getSize(), zipPath);
        file.transferTo(zipPath.toFile());
        Long versionId = createVersion(projectId, "ZIP", null, importRoot.toFile().getAbsolutePath());
        projectVersionMapper.updateMessage(versionId, "ZIP 上传完成，等待后台解压");
        log.info("ZIP 上传落盘完成 projectId={} versionId={} fileName={} size={} elapsedMs={}", projectId,
                versionId, file.getOriginalFilename(), file.getSize(), System.currentTimeMillis() - startedAt);
        try {
            sourceImportTaskRunner.runZipImport(projectId, versionId, zipPath, importRoot.resolve("source"));
        } catch (RuntimeException exception) {
            projectVersionMapper.deleteByIdAndProject(versionId, projectId);
            FileUtils.deleteQuietly(importRoot.toFile());
            throw new IllegalStateException("源码导入任务队列已满，请稍后重试", exception);
        }
        return versionId;
    }

    private Long createVersion(Long projectId, String sourceType, String branchName, String sourcePath) {
        ProjectVersion version = new ProjectVersion();
        version.setProjectId(projectId);
        version.setSourceType(sourceType);
        version.setBranchName(branchName);
        version.setSourcePath(sourcePath);
        projectVersionMapper.insertVersion(version);
        return version.getId();
    }

    private String[] buildGitCommand(GitImportRequest request) {
        if (request.getBranchName() == null || request.getBranchName().trim().isEmpty()) {
            return new String[]{"git", "clone", request.getRepoUrl(), "source"};
        }
        return new String[]{"git", "clone", "-b", request.getBranchName(), request.getRepoUrl(), "source"};
    }

    private Path workspacePath(Long projectId, String type) throws IOException {
        Path path = new File(appProperties.getWorkspaceRoot(),
                "project-" + projectId + File.separator + type + "-" + UUID.randomUUID())
                .toPath().toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    /**
     * 清掉该项目已有的全部版本：删代码图谱（code_node/code_edge）和磁盘源码目录，再删版本行。
     * 一个项目只保留一份当前源码，重导即覆盖，DB 和磁盘不再随导入次数无限增长。
     */
    private void pruneProjectVersions(Long projectId) {
        List<ProjectVersion> versions = projectVersionMapper.listByProject(projectId);
        for (ProjectVersion version : versions) {
            codeGraphRepository.clearVersion(projectId, version.getId());
            deleteSourceDir(projectId, version.getSourcePath());
        }
        projectVersionMapper.deleteByProject(projectId);
    }

    /**
     * 删除一次导入落地的源码目录（project-X 下的 type-uuid 那层），尽量把磁盘清干净。
     */
    private void deleteSourceDir(Long projectId, String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return;
        }
        File importRoot = locateImportRoot(new File(sourcePath), projectId);
        FileUtils.deleteQuietly(importRoot);
    }

    private File locateImportRoot(File dir, Long projectId) {
        String projectDirName = "project-" + projectId;
        File current = dir;
        while (current != null && current.getParentFile() != null) {
            if (projectDirName.equals(current.getParentFile().getName())) {
                return current;
            }
            current = current.getParentFile();
        }
        return dir;
    }

}
