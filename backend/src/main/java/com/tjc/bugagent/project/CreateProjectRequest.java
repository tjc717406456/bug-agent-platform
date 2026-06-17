package com.tjc.bugagent.project;

import javax.validation.constraints.NotBlank;

/**
 * Request for creating a project.
 */
public class CreateProjectRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String code;
    private String description;

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
}

