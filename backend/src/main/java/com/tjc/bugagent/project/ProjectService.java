package com.tjc.bugagent.project;

import com.tjc.bugagent.project.mapper.ProjectDatasourceMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import com.tjc.bugagent.project.mapper.ProjectVersionMapper;
import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ProjectVersionMapper projectVersionMapper;

    public ProjectService(JdbcTemplate jdbcTemplate, ProjectDatasourceMapper projectDatasourceMapper,
                          ProjectMapper projectMapper, ProjectVersionMapper projectVersionMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectDatasourceMapper = projectDatasourceMapper;
        this.projectMapper = projectMapper;
        this.projectVersionMapper = projectVersionMapper;
    }

    /**
     * 按项目名称和编码查询项目。
     */
    public List<Project> listProjects(String name, String code) {
        return projectMapper.list(name, code);
    }

    /**
     * 创建项目。
     */
    public Project createProject(CreateProjectRequest request) {
        validateProject(request);
        Project project = new Project();
        project.setName(request.getName().trim());
        project.setCode(request.getCode().trim());
        project.setDescription(safe(request.getDescription()));
        projectMapper.insert(project);
        return getProjectByCode(request.getCode().trim());
    }

    /**
     * 更新项目基础信息。
     */
    public Project updateProject(Long projectId, CreateProjectRequest request) {
        validateProject(request);
        Project oldProject = getProject(projectId);
        if (oldProject == null) {
            throw new IllegalArgumentException("Project not found");
        }
        if (projectMapper.countByCodeExcludingId(request.getCode().trim(), projectId) > 0) {
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
        projectVersionMapper.deleteByProject(projectId);
        projectMapper.deleteById(projectId);
    }

    public Project getProject(Long projectId) {
        return projectMapper.findById(projectId);
    }

    private Project getProjectByCode(String code) {
        return projectMapper.findByCode(code);
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
