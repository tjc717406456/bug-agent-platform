package com.tjc.bugagent.source;

import javax.validation.constraints.NotBlank;

/**
 * Request for importing source from Git.
 */
public class GitImportRequest {
    @NotBlank
    private String repoUrl;
    private String branchName;
    private String accessToken;

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
}

