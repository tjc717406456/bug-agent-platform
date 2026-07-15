package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.DatasourceSelection;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次 Agent 运行的服务端授权范围。工具只能使用这里绑定的项目、版本和数据源。
 */
public final class ProjectExecutionScope {
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(from|join)\\s+([`\\w.]+)");

    private final String taskId;
    private final Long ownerId;
    private final Long projectId;
    private final Long versionId;
    private final String databaseAccessLevel;
    private final ProjectDatasource schemaDatasource;
    private final ProjectDatasource businessDatasource;
    private final Set<String> allowedTables;
    private final Set<String> allowedTools;

    private ProjectExecutionScope(String taskId, Long ownerId, Long projectId, Long versionId,
                                  String databaseAccessLevel, ProjectDatasource schemaDatasource,
                                  ProjectDatasource businessDatasource, Set<String> allowedTools) {
        this.taskId = taskId;
        this.ownerId = ownerId;
        this.projectId = projectId;
        this.versionId = versionId;
        assertDatasourceProject(projectId, schemaDatasource);
        assertDatasourceProject(projectId, businessDatasource);
        this.databaseAccessLevel = databaseAccessLevel == null ? DatasourceSelection.NONE : databaseAccessLevel;
        this.schemaDatasource = schemaDatasource;
        this.businessDatasource = businessDatasource;
        ProjectDatasource whitelistDatasource = businessDatasource == null ? schemaDatasource : businessDatasource;
        this.allowedTables = parseTables(whitelistDatasource == null ? null : whitelistDatasource.getWhitelistTables());
        this.allowedTools = allowedTools == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(allowedTools));
    }

    public static ProjectExecutionScope create(String taskId, Long ownerId, Long projectId, Long versionId,
                                                ProjectDatasource datasource, String... allowedTools) {
        String level = datasource == null ? DatasourceSelection.NONE : DatasourceSelection.BUSINESS_DATA;
        return new ProjectExecutionScope(taskId, ownerId, projectId, versionId, level, datasource, datasource,
                new LinkedHashSet<String>(Arrays.asList(allowedTools)));
    }

    /** 创建带结构库和业务库隔离的执行范围。 */
    public static ProjectExecutionScope create(String taskId, Long ownerId, Long projectId, Long versionId,
                                                DatasourceSelection selection, String... allowedTools) {
        return new ProjectExecutionScope(taskId, ownerId, projectId, versionId, selection.getAccessLevel(),
                selection.getSchemaDatasource(), selection.getBusinessDatasource(),
                new LinkedHashSet<String>(Arrays.asList(allowedTools)));
    }

    public boolean allowsTool(String name) {
        if ("query_database".equals(name)
                && (!DatasourceSelection.BUSINESS_DATA.equals(databaseAccessLevel) || businessDatasource == null)) {
            return false;
        }
        if ("describe_tables".equals(name) && schemaDatasource == null) {
            return false;
        }
        return allowedTools.isEmpty() || allowedTools.contains(name);
    }

    /** 从父任务派生工具更少的子 Agent 作用域，项目、版本、数据源和表白名单保持不变。 */
    public ProjectExecutionScope child(String childTaskId, String... tools) {
        Set<String> childTools = new LinkedHashSet<String>(Arrays.asList(tools));
        childTools.removeIf(tool -> !allowsTool(tool));
        if (childTools.isEmpty()) {
            throw new IllegalArgumentException("子 Agent 没有可继承的工具权限");
        }
        return new ProjectExecutionScope(childTaskId, ownerId, projectId, versionId, databaseAccessLevel,
                schemaDatasource, businessDatasource, childTools);
    }

    public boolean allowsTables(Iterable<String> tables) {
        if (allowedTables.isEmpty()) {
            return true;
        }
        for (String table : tables) {
            if (!allowedTables.contains(normalizeTable(table))) {
                return false;
            }
        }
        return true;
    }

    public boolean allowsSql(String sql) {
        if (allowedTables.isEmpty() || sql == null) {
            return true;
        }
        Matcher matcher = SQL_TABLE.matcher(sql);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!allowedTables.contains(normalizeTable(matcher.group(2)))) {
                return false;
            }
        }
        return found;
    }

    private static Set<String> parseTables(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<String>();
        for (String table : value.split("[,，\\s]+")) {
            if (!table.trim().isEmpty()) {
                result.add(normalizeTable(table));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String normalizeTable(String table) {
        String clean = table == null ? "" : table.replace("`", "").trim();
        int dot = clean.lastIndexOf('.');
        return (dot >= 0 ? clean.substring(dot + 1) : clean).toLowerCase(Locale.ROOT);
    }

    private static void assertDatasourceProject(Long projectId, ProjectDatasource datasource) {
        if (datasource != null && datasource.getProjectId() != null && !datasource.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("数据源不属于当前项目");
        }
    }

    public String getTaskId() { return taskId; }
    public Long getOwnerId() { return ownerId; }
    public Long getProjectId() { return projectId; }
    public Long getVersionId() { return versionId; }
    public ProjectDatasource getDatasource() { return businessDatasource == null ? schemaDatasource : businessDatasource; }
    public String getDatabaseAccessLevel() { return databaseAccessLevel; }
    public ProjectDatasource getSchemaDatasource() { return schemaDatasource; }
    public ProjectDatasource getBusinessDatasource() { return businessDatasource; }
    public Set<String> getAllowedTables() { return allowedTables; }
    public Set<String> getAllowedTools() { return allowedTools; }
}
