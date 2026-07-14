package com.tjc.bugagent.project;

import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.mapper.ProjectDatasourceMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import com.tjc.bugagent.project.mapper.ProjectMemberMapper;
import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.util.List;

/**
 * Handles project metadata and datasource bindings.
 */
@Service
public class ProjectService {
    private final JdbcTemplate jdbcTemplate;
    private final ProjectDatasourceMapper projectDatasourceMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectVersionMapper projectVersionMapper;
    private final AppProperties appProperties;

    public ProjectService(JdbcTemplate jdbcTemplate, ProjectDatasourceMapper projectDatasourceMapper,
                          ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                          ProjectVersionMapper projectVersionMapper, AppProperties appProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectDatasourceMapper = projectDatasourceMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectVersionMapper = projectVersionMapper;
        this.appProperties = appProperties;
    }

    /**
     * 按项目名称和编码查询项目。ownerId 为空表示管理员，返回全部项目。
     */
    public List<Project> listProjects(String name, String code, Long ownerId) {
        return projectMapper.list(name, code, ownerId);
    }

    /**
     * 创建项目，归属到指定用户。项目编码只在同一所有者下唯一。
     */
    public Project createProject(CreateProjectRequest request, Long ownerId) {
        validateProject(request);
        Project project = new Project();
        project.setName(request.getName().trim());
        project.setCode(request.getCode().trim());
        project.setOwnerId(ownerId);
        project.setDescription(safe(request.getDescription()));
        projectMapper.insert(project);
        // 用回填的自增 id 重查：code 已不再全局唯一，按 code 回查会捞到别人的同名项目
        return getProject(project.getId());
    }

    /**
     * 更新项目基础信息。编码判重限定在该项目的所有者名下。
     */
    public Project updateProject(Long projectId, CreateProjectRequest request) {
        validateProject(request);
        Project oldProject = getProject(projectId);
        if (oldProject == null) {
            throw new IllegalArgumentException("Project not found");
        }
        if (projectMapper.countByCodeExcludingId(request.getCode().trim(), projectId, oldProject.getOwnerId()) > 0) {
            throw new IllegalArgumentException("Project code already exists");
        }
        projectMapper.update(projectId, request.getName().trim(), request.getCode().trim(), safe(request.getDescription()));
        return getProject(projectId);
    }

    /**
     * 删除项目及其关联数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long projectId) {
        jdbcTemplate.update("delete from analysis_record where project_id = ?", projectId);
        jdbcTemplate.update("delete from code_edge where project_id = ?", projectId);
        jdbcTemplate.update("delete from code_node where project_id = ?", projectId);
        jdbcTemplate.update("delete from project_datasource where project_id = ?", projectId);
        projectMemberMapper.deleteByProject(projectId);
        projectVersionMapper.deleteByProject(projectId);
        projectMapper.deleteById(projectId);
        deleteProjectFilesAfterCommit(projectId);
    }

    /**
     * 数据库删除提交成功后再清理项目源码和截图，避免事务回滚时文件已经没了。
     */
    private void deleteProjectFilesAfterCommit(Long projectId) {
        Runnable cleanup = () -> {
            File workspace = new File(appProperties.getWorkspaceRoot()).getAbsoluteFile();
            FileUtils.deleteQuietly(new File(workspace, "project-" + projectId));
            FileUtils.deleteQuietly(new File(workspace,
                    "screenshots" + File.separator + "project-" + projectId));
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    public List<Long> listMembers(Long projectId) {
        return projectMemberMapper.listUserIds(projectId);
    }

    /** 全量替换项目可见范围：先清后插，勾选列表即最终状态。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveMembers(Long projectId, List<Long> userIds) {
        projectMemberMapper.deleteByProject(projectId);
        if (userIds != null && !userIds.isEmpty()) {
            projectMemberMapper.insertAll(projectId, userIds);
        }
    }

    public Project getProject(Long projectId) {
        return projectMapper.findById(projectId);
    }

    public List<ProjectVersion> listVersions(Long projectId) {
        return projectVersionMapper.listByProject(projectId);
    }

    public ProjectVersion latestReadyVersion(Long projectId) {
        return projectVersionMapper.latestReady(projectId);
    }

    public ProjectVersion getVersion(Long versionId) {
        return projectVersionMapper.findById(versionId);
    }

    /**
     * 删除某个版本：连带清掉代码图谱（code_node/code_edge）和磁盘源码目录。
     */
    public void deleteVersion(Long projectId, Long versionId) {
        List<String> paths = projectVersionMapper.findSourcePaths(versionId, projectId);
        jdbcTemplate.update("delete from code_edge where project_id = ? and version_id = ?", projectId, versionId);
        jdbcTemplate.update("delete from code_node where project_id = ? and version_id = ?", projectId, versionId);
        projectVersionMapper.deleteByIdAndProject(versionId, projectId);
        if (!paths.isEmpty()) {
            deleteSourceDir(projectId, paths.get(0));
        }
    }

    private void deleteSourceDir(Long projectId, String sourcePath) {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return;
        }
        String projectDirName = "project-" + projectId;
        File current = new File(sourcePath);
        while (current != null && current.getParentFile() != null) {
            if (projectDirName.equals(current.getParentFile().getName())) {
                break;
            }
            current = current.getParentFile();
        }
        FileUtils.deleteQuietly(current == null ? new File(sourcePath) : current);
    }

    public void saveDatasource(Long projectId, SaveDatasourceRequest request) {
        if (projectDatasourceMapper.countByProjectEnv(projectId, request.getEnv()) > 0) {
            projectDatasourceMapper.updateByProjectEnv(request.getDbhubKey(), "", projectId, request.getEnv());
            return;
        }
        projectDatasourceMapper.insert(projectId, request.getEnv(), request.getDbhubKey(), "");
    }

    public List<ProjectDatasource> listDatasources(Long projectId) {
        return projectDatasourceMapper.listByProject(projectId);
    }

    public ProjectDatasource firstEnabledDatasource(Long projectId) {
        return projectDatasourceMapper.findFirstEnabled(projectId);
    }

    private void validateProject(CreateProjectRequest request) {
        if (request == null || isBlank(request.getName()) || isBlank(request.getCode())) {
            throw new IllegalArgumentException("Missing project name or code");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
