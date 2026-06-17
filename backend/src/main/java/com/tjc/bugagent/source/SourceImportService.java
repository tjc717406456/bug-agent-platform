package com.tjc.bugagent.source;

import com.tjc.bugagent.codegraph.CodeGraphIndexService;
import com.tjc.bugagent.config.AppProperties;
import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports project source and starts indexing.
 */
@Service
public class SourceImportService {
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final CodeGraphIndexService codeGraphIndexService;

    public SourceImportService(AppProperties appProperties, JdbcTemplate jdbcTemplate, CodeGraphIndexService codeGraphIndexService) {
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
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
        Long versionId = createVersion(projectId, "GIT", request.getBranchName(), files[0].getAbsolutePath());
        codeGraphIndexService.indexAsync(projectId, versionId, files[0].toPath());
        return versionId;
    }

    public Long importZip(Long projectId, MultipartFile file) throws IOException {
        Path target = workspacePath(projectId, "zip");
        unzip(file, target);
        Path sourceRoot = findSourceRoot(target);
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

    private Long createVersion(Long projectId, String sourceType, String branchName, String sourcePath) {
        jdbcTemplate.update(
                "insert into project_version(project_id, source_type, branch_name, source_path, index_status, created_at) values (?, ?, ?, ?, 'PENDING', now())",
                projectId, sourceType, branchName, sourcePath);
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
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
