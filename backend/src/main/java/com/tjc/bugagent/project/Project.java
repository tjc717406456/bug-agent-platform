package com.tjc.bugagent.project;

/**
 * Project metadata.
 */
public class Project {
    private Long id;
    private String name;
    private String code;
    private String description;
    private String environments = "prod,test";
    private boolean schemaConsistent = true;
    private String schemaReferenceEnv = "test";
    /** 所属用户；项目私有，仅所有者与管理员可见 */
    private Long ownerId;

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEnvironments() {
        return environments;
    }

    public void setEnvironments(String environments) {
        this.environments = environments;
    }

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
