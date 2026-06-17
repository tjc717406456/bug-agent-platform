package com.tjc.bugagent.project;

/**
 * dbhub datasource binding for a project.
 */
public class ProjectDatasource {
    private Long id;
    private Long projectId;
    private String env;
    private String dbhubKey;
    private String whitelistTables;
    private boolean enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getDbhubKey() {
        return dbhubKey;
    }

    public void setDbhubKey(String dbhubKey) {
        this.dbhubKey = dbhubKey;
    }

    public String getWhitelistTables() {
        return whitelistTables;
    }

    public void setWhitelistTables(String whitelistTables) {
        this.whitelistTables = whitelistTables;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

