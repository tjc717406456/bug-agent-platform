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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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
        project.setEnvironments(normalizeEnvironments(request.getEnvironments()));
        project.setSchemaReferenceEnv(parseEnvironments(project.getEnvironments()).iterator().next());
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
        String environments = normalizeEnvironments(request.getEnvironments());
        assertBoundDatasourceEnvironments(projectId, environments);
        projectMapper.update(projectId, request.getName().trim(), request.getCode().trim(),
                safe(request.getDescription()), environments);
        if (!parseEnvironments(environments).contains(normalizeEnvironment(oldProject.getSchemaReferenceEnv()))) {
            projectMapper.updateDatasourcePolicy(projectId, oldProject.isSchemaConsistent(),
                    parseEnvironments(environments).iterator().next());
        }
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
        String env = normalizeEnvironment(request.getEnv());
        assertProjectEnvironment(projectId, env);
        if (projectDatasourceMapper.countByProjectEnv(projectId, env) > 0) {
            projectDatasourceMapper.updateByProjectEnv(request.getDbhubKey().trim(), "", projectId, env);
            return;
        }
        projectDatasourceMapper.insert(projectId, env, request.getDbhubKey().trim(), "");
    }

    /** 保存跨环境结构复用规则。 */
    public void saveDatasourcePolicy(Long projectId, ProjectDatasourcePolicyRequest request) {
        String referenceEnv = normalizeEnvironment(request.getSchemaReferenceEnv());
        assertProjectEnvironment(projectId, referenceEnv);
        projectMapper.updateDatasourcePolicy(projectId, request.isSchemaConsistent(),
                referenceEnv);
    }

    public List<ProjectDatasource> listDatasources(Long projectId) {
        return projectDatasourceMapper.listByProject(projectId);
    }

    /**
     * 按问题环境解析本次数据库权限。AUTO 不会把其他环境的业务数据当成当前环境事实。
     */
    public DatasourceSelection resolveDatasourceSelection(Long projectId, String environment, String policy) {
        String requested = normalizePolicy(policy);
        Project project = getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        String env = resolveProjectEnvironment(project, environment);
        ProjectDatasource business = projectDatasourceMapper.findEnabledByProjectEnv(projectId, env);
        ProjectDatasource reference = null;
        if (project.isSchemaConsistent()) {
            reference = projectDatasourceMapper.findEnabledByProjectEnv(projectId,
                    normalizeEnvironment(project.getSchemaReferenceEnv()));
        }
        ProjectDatasource schema = business != null ? business : reference;
        if (DatasourceSelection.NONE.equals(requested)) {
            return new DatasourceSelection(env, DatasourceSelection.NONE, null, null);
        }
        if (DatasourceSelection.SCHEMA_ONLY.equals(requested)) {
            return new DatasourceSelection(env, schema == null ? DatasourceSelection.NONE : DatasourceSelection.SCHEMA_ONLY,
                    schema, null);
        }
        if (DatasourceSelection.BUSINESS_DATA.equals(requested)) {
            if (business == null) {
                throw new IllegalStateException("未配置 " + env + " 环境数据源，不能核对业务数据");
            }
            return new DatasourceSelection(env, DatasourceSelection.BUSINESS_DATA, business, business);
        }
        if (business != null) {
            return new DatasourceSelection(env, DatasourceSelection.BUSINESS_DATA, business, business);
        }
        return new DatasourceSelection(env, schema == null ? DatasourceSelection.NONE : DatasourceSelection.SCHEMA_ONLY,
                schema, null);
    }

    /** 按分析记录固化的数据源恢复追问权限，不重新挑最新数据源。 */
    public DatasourceSelection restoreDatasourceSelection(Long projectId, String environment, String accessLevel,
                                                           Long schemaDatasourceId, Long businessDatasourceId) {
        String env = normalizeEnvironment(environment);
        String level = normalizeAccessLevel(accessLevel);
        ProjectDatasource schema = schemaDatasourceId == null ? null
                : projectDatasourceMapper.findEnabledByIdAndProject(schemaDatasourceId, projectId);
        ProjectDatasource business = businessDatasourceId == null ? null
                : projectDatasourceMapper.findEnabledByIdAndProject(businessDatasourceId, projectId);
        if (DatasourceSelection.BUSINESS_DATA.equals(level)) {
            if (business == null || !env.equalsIgnoreCase(business.getEnv())) {
                return new DatasourceSelection(env, schema == null ? DatasourceSelection.NONE : DatasourceSelection.SCHEMA_ONLY,
                        schema, null);
            }
            return new DatasourceSelection(env, level, business, business);
        }
        if (DatasourceSelection.SCHEMA_ONLY.equals(level) && schema != null) {
            return new DatasourceSelection(env, level, schema, null);
        }
        return new DatasourceSelection(env, DatasourceSelection.NONE, null, null);
    }

    private String normalizePolicy(String policy) {
        if (policy == null || policy.trim().isEmpty() || "AUTO".equalsIgnoreCase(policy)) {
            return "AUTO";
        }
        String value = policy.trim().toUpperCase(Locale.ROOT);
        if (!DatasourceSelection.NONE.equals(value) && !DatasourceSelection.SCHEMA_ONLY.equals(value)
                && !DatasourceSelection.BUSINESS_DATA.equals(value)) {
            throw new IllegalArgumentException("不支持的数据库验证策略: " + policy);
        }
        return value;
    }

    private String normalizeAccessLevel(String accessLevel) {
        if (DatasourceSelection.BUSINESS_DATA.equalsIgnoreCase(accessLevel)) {
            return DatasourceSelection.BUSINESS_DATA;
        }
        if (DatasourceSelection.SCHEMA_ONLY.equalsIgnoreCase(accessLevel)) {
            return DatasourceSelection.SCHEMA_ONLY;
        }
        return DatasourceSelection.NONE;
    }

    private String normalizeEnvironment(String environment) {
        return environment == null || environment.trim().isEmpty()
                ? "prod" : environment.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveProjectEnvironment(Project project, String environment) {
        Set<String> environments = parseEnvironments(project.getEnvironments());
        if (environments.isEmpty()) {
            throw new IllegalStateException("项目未配置环境");
        }
        String selected = environment == null || environment.trim().isEmpty()
                ? environments.iterator().next() : normalizeEnvironment(environment);
        if (!environments.contains(selected)) {
            throw new IllegalArgumentException("环境 " + selected + " 不属于当前项目");
        }
        return selected;
    }

    /** 规范项目环境列表，去重后使用逗号保存。 */
    private String normalizeEnvironments(String environments) {
        Set<String> values = parseEnvironments(environments);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("项目至少配置一个环境");
        }
        return String.join(",", values);
    }

    private Set<String> parseEnvironments(String environments) {
        Set<String> values = new LinkedHashSet<String>();
        if (environments == null) {
            return values;
        }
        for (String item : environments.split("[,，\\s]+")) {
            if (item.trim().isEmpty()) {
                continue;
            }
            String env = normalizeEnvironment(item);
            if (env.length() > 32) {
                throw new IllegalArgumentException("环境标识不能超过32个字符: " + env);
            }
            values.add(env);
        }
        return values;
    }

    private void assertProjectEnvironment(Long projectId, String environment) {
        Project project = getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        if (!parseEnvironments(project.getEnvironments()).contains(normalizeEnvironment(environment))) {
            throw new IllegalArgumentException("环境 " + environment + " 不属于当前项目");
        }
    }

    private void assertBoundDatasourceEnvironments(Long projectId, String environments) {
        Set<String> allowed = parseEnvironments(environments);
        for (ProjectDatasource datasource : projectDatasourceMapper.listByProject(projectId)) {
            if (!allowed.contains(normalizeEnvironment(datasource.getEnv()))) {
                throw new IllegalArgumentException("环境 " + datasource.getEnv() + " 已绑定数据源，不能从项目环境中删除");
            }
        }
    }

    private void validateProject(CreateProjectRequest request) {
        if (request == null || isBlank(request.getName()) || isBlank(request.getCode())
                || isBlank(request.getEnvironments())) {
            throw new IllegalArgumentException("Missing project name, code or environments");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
