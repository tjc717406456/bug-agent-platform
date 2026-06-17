package com.tjc.bugagent.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public List<Project> listProjects() {
        return jdbcTemplate.query("select id, name, code, description from project order by id desc", new ProjectMapper());
    }

    public Project createProject(CreateProjectRequest request) {
        jdbcTemplate.update(
                "insert into project(name, code, description, created_at, updated_at) values (?, ?, ?, now(), now())",
                request.getName(), request.getCode(), request.getDescription());
        return jdbcTemplate.queryForObject("select id, name, code, description from project where code = ?", new ProjectMapper(), request.getCode());
    }

    public List<ProjectVersion> listVersions(Long projectId) {
        return jdbcTemplate.query(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message from project_version where project_id = ? order by id desc",
                new ProjectVersionMapper(), projectId);
    }

    public ProjectVersion latestReadyVersion(Long projectId) {
        List<ProjectVersion> versions = jdbcTemplate.query(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message from project_version where project_id = ? and index_status = 'SUCCESS' order by id desc limit 1",
                new ProjectVersionMapper(), projectId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public ProjectVersion getVersion(Long versionId) {
        return jdbcTemplate.queryForObject(
                "select id, project_id, source_type, branch_name, commit_id, source_path, index_status, index_message from project_version where id = ?",
                new ProjectVersionMapper(), versionId);
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
