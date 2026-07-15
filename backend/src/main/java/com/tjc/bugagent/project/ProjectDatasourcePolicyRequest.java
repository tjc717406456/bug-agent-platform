package com.tjc.bugagent.project;

import javax.validation.constraints.NotBlank;

/**
 * 项目跨环境数据库结构策略。
 */
public class ProjectDatasourcePolicyRequest {
    private boolean schemaConsistent;
    @NotBlank
    private String schemaReferenceEnv;

    public boolean isSchemaConsistent() {
        return schemaConsistent;
    }

    public void setSchemaConsistent(boolean schemaConsistent) {
        this.schemaConsistent = schemaConsistent;
    }

    public String getSchemaReferenceEnv() {
        return schemaReferenceEnv;
    }

    public void setSchemaReferenceEnv(String schemaReferenceEnv) {
        this.schemaReferenceEnv = schemaReferenceEnv;
    }
}
