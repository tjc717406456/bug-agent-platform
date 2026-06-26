package com.tjc.bugagent.source;

import com.tjc.bugagent.codegraph.CodeGraphIndexService;
import com.tjc.bugagent.codegraph.CodeGraphRepository;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectVersion;
import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports project source and starts indexing.
 */
@Service
public class SourceImportService {
    private final AppProperties appProperties;
    private final ProjectVersionMapper projectVersionMapper;
    private final CodeGraphRepository codeGraphRepository;
    private final CodeGraphIndexService codeGraphIndexService;

    public SourceImportService(AppProperties appProperties, ProjectVersionMapper projectVersionMapper,
                               CodeGraphRepository codeGraphRepository, CodeGraphIndexService codeGraphIndexService) {
        this.appProperties = appProperties;
        this.projectVersionMapper = projectVersionMapper;
        this.codeGraphRepository = codeGraphRepository;
        this.codeGraphIndexService = codeGraphIndexService;
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
        Path target = workspacePath(projectId, "zip");
        unzip(file, target);
        Path sourceRoot = findSourceRoot(target);
        // 新源码已就绪，清掉该项目的旧版本（图谱 + 磁盘），保证一个项目只留当前一份
        pruneProjectVersions(projectId);
        Long versionId = createVersion(projectId, "ZIP", null, sourceRoot.toFile().getAbsolutePath());
        codeGraphIndexService.indexAsync(projectId, versionId, sourceRoot);
        return versionId;
    }

    private String[] buildGitCommand(GitImportRequest request) {
        if (request.getBranchName() == null || request.getBranchName().trim().isEmpty()) {
            return new String[]{"git", "clone", request.getRepoUrl(), "source"};
        }
        return new String[]{"git", "clone", "-b", request.getBranchName(), request.getRepoUrl(), "source"};
    }

    private Path workspacePath(Long projectId, String type) throws IOException {
        Path path = new File(appProperties.getWorkspaceRoot(), "project-" + projectId + File.separator + type + "-" + UUID.randomUUID()).toPath();
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

    private Long createVersion(Long projectId, String sourceType, String branchName, String sourcePath) {
        ProjectVersion version = new ProjectVersion();
        version.setProjectId(projectId);
        version.setSourceType(sourceType);
        version.setBranchName(branchName);
        version.setSourcePath(sourcePath);
        projectVersionMapper.insertVersion(version);
        return version.getId();
    }

    private void unzip(MultipartFile file, Path target) throws IOException {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target);
        try (ZipInputStream inputStream = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                Path entryPath = target.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(target)) {
                    throw new IllegalStateException("invalid zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(inputStream, entryPath);
                }
            }
        }
    }

    private Path findSourceRoot(Path target) throws IOException {
        if (Files.exists(target.resolve("pom.xml")) || Files.exists(target.resolve("build.gradle"))) {
            return target;
        }
        return Files.list(target)
                .filter(Files::isDirectory)
                .filter(path -> Files.exists(path.resolve("pom.xml")) || Files.exists(path.resolve("build.gradle")) || Files.exists(path.resolve("src")))
                .findFirst()
                .orElse(target);
    }
}
