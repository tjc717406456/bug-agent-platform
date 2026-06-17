package com.tjc.bugagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-level configurable properties.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String workspaceRoot;

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }
}
