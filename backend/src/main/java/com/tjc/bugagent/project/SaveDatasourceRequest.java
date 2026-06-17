package com.tjc.bugagent.project;

import javax.validation.constraints.NotBlank;

/**
 * Request for saving a dbhub datasource binding.
 */
public class SaveDatasourceRequest {
    @NotBlank
    private String env;
    @NotBlank
    private String dbhubKey;

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

}
