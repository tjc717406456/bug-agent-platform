package com.tjc.bugagent.project;

import org.apache.commons.io.FileUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles project metadata and datasource bindings.
 */
@Service
public class ProjectService {
    private final JdbcTemplate jdbcTemplate;

    public ProjectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按项目名称和编码查询项目。
     */
    public List<Project> listProjects(String name, String code) {
        StringBuilder sql = new StringBuilder("select id, name, code, description from project where 1 = 1");
        List<Object> params = new ArrayList<Object>();
        if (!isBlank(name)) {
            sql.append(" and name like ?");
            params.add("%" + name.trim() + "%");
        }
        if (!isBlank(code)) {
            sql.append(" and code like ?");
            params.add("%" + code.trim() + "%");
        }
        sql.append(" order by id desc");
        return jdbcTemplate.query(sql.toString(), new ProjectMapper(), params.toArray());
    }

    /**
     * 创建项目。
     */
    public Project createProject(CreateProjectRequest request) {
        validateProject(request);
        jdbcTemplate.update(
                "insert into project(name, code, description, created_at, updated_at) values (?, ?, ?, now(), now())",
                request.getName().trim(), request.getCode().trim(), safe(request.getDescription()));
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
        Integer codeCount = jdbcTemplate.queryForObject(
                "select count(1) from project where code = ? and id <> ?",
                Integer.class, request.getCode().trim(), projectId);
        if (codeCount != null && codeCount > 0) {
            throw new IllegalArgumentException("Project code already exists");
        }
        jdbcTemplate.update(
                "update project set name = ?, code = ?, description = ?, updated_at = now() where id = ?",
                request.getName().trim(), request.getCode().trim(), safe(request.getDescription()), projectId);
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
        jdbcTemplate.update("delete from project_version where project_id = ?", projectId);
        jdbcTemplate.update("delete from project where id = ?", projectId);
    }

    public List<Project> listProjects() {
        return listProjects(null, null);
    }

    public Project getProject(Long projectId) {
        List<Project> projects = jdbcTemplate.query(
                "select id, name, code, description from project where id = ?",
                new ProjectMapper(), projectId);
        return projects.isEmpty() ? null : projects.get(0);
    }

    private Project getProjectByCode(String code) {
        return jdbcTemplate.queryForObject("select id, name, code, description from project where code = ?", new ProjectMapper(), code);
    }

    public List<ProjectVersion> listVersions(Long projectId) {
        return jdbcTemplate.query(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message, coalesce(indexed_at, created_at) as updated_at from project_version where project_id = ? order by id desc",
                new ProjectVersionMapper(), projectId);
    }

    public ProjectVersion latestReadyVersion(Long projectId) {
        List<ProjectVersion> versions = jdbcTemplate.query(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message, coalesce(indexed_at, created_at) as updated_at from project_version where project_id = ? and index_status = 'SUCCESS' order by id desc limit 1",
                new ProjectVersionMapper(), projectId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public ProjectVersion getVersion(Long versionId) {
        return jdbcTemplate.queryForObject(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message, coalesce(indexed_at, created_at) as updated_at from project_version where id = ?",
                new ProjectVersionMapper(), versionId);
    }

    /**
     * 删除某个版本：连带清掉代码图谱（code_node/code_edge）和磁盘源码目录。
     */
    public void deleteVersion(Long projectId, Long versionId) {
        List<String> paths = jdbcTemplate.queryForList(
                "select source_path from project_version where id = ? and project_id = ?", String.class, versionId, projectId);
        jdbcTemplate.update("delete from code_edge where project_id = ? and version_id = ?", projectId, versionId);
        jdbcTemplate.update("delete from code_node where project_id = ? and version_id = ?", projectId, versionId);
        jdbcTemplate.update("delete from project_version where id = ? and project_id = ?", versionId, projectId);
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
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from project_datasource where project_id = ? and env = ?",
                Integer.class, projectId, request.getEnv());
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "update project_datasource set dbhub_key = ?, whitelist_tables = ?, enabled = 1, updated_at = now() where project_id = ? and env = ?",
                    request.getDbhubKey(), "", projectId, request.getEnv());
            return;
        }
        jdbcTemplate.update(
                "insert into project_datasource(project_id, env, dbhub_key, whitelist_tables, enabled, created_at, updated_at) values (?, ?, ?, ?, 1, now(), now())",
                projectId, request.getEnv(), request.getDbhubKey(), "");
    }

    public List<ProjectDatasource> listDatasources(Long projectId) {
        return jdbcTemplate.query(
                "select id, project_id, env, dbhub_key, whitelist_tables, enabled from project_datasource where project_id = ? order by id desc",
                new ProjectDatasourceMapper(), projectId);
    }

    public ProjectDatasource firstEnabledDatasource(Long projectId) {
        List<ProjectDatasource> list = jdbcTemplate.query(
                "select id, project_id, env, dbhub_key, whitelist_tables, enabled from project_datasource where project_id = ? and enabled = 1 order by id desc limit 1",
                new ProjectDatasourceMapper(), projectId);
        return list.isEmpty() ? null : list.get(0);
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

    private static class ProjectMapper implements RowMapper<Project> {
        @Override
        public Project mapRow(ResultSet rs, int rowNum) throws SQLException {
            Project project = new Project();
            project.setId(rs.getLong("id"));
            project.setName(rs.getString("name"));
            project.setCode(rs.getString("code"));
            project.setDescription(rs.getString("description"));
            return project;
        }
    }

    private static class ProjectVersionMapper implements RowMapper<ProjectVersion> {
        @Override
        public ProjectVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            ProjectVersion version = new ProjectVersion();
            version.setId(rs.getLong("id"));
            version.setProjectId(rs.getLong("project_id"));
            version.setSourceType(rs.getString("source_type"));
            version.setBranchName(rs.getString("branch_name"));
            version.setCommitId(rs.getString("commit_id"));
            version.setSourcePath(rs.getString("source_path"));
            version.setIndexStatus(rs.getString("index_status"));
            version.setIndexMessage(rs.getString("index_message"));
            version.setUpdatedAt(rs.getString("updated_at"));
            return version;
        }
    }

    private static class ProjectDatasourceMapper implements RowMapper<ProjectDatasource> {
        @Override
        public ProjectDatasource mapRow(ResultSet rs, int rowNum) throws SQLException {
            ProjectDatasource datasource = new ProjectDatasource();
            datasource.setId(rs.getLong("id"));
            datasource.setProjectId(rs.getLong("project_id"));
            datasource.setEnv(rs.getString("env"));
            datasource.setDbhubKey(rs.getString("dbhub_key"));
            datasource.setWhitelistTables(rs.getString("whitelist_tables"));
            datasource.setEnabled(rs.getBoolean("enabled"));
            return datasource;
        }
    }
}
