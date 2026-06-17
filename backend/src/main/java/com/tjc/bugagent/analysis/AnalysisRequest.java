package com.tjc.bugagent.analysis;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * Request for starting a bug analysis.
 */
public class AnalysisRequest {
    @NotNull
    private Long projectId;
    private Long versionId;
    @NotBlank
    private String apiPath;
    private String userDescription;
    private String requestBody;
    private String responseBody;
    private String stackTrace;
    private String screenshotPaths;
    private String traceId;
    private String requestTime;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public String getUserDescription() {
        return userDescription;
    }

    public void setUserDescription(String userDescription) {
        this.userDescription = userDescription;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getScreenshotPaths() {
        return screenshotPaths;
    }

    public void setScreenshotPaths(String screenshotPaths) {
        this.screenshotPaths = screenshotPaths;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(String requestTime) {
        this.requestTime = requestTime;
    }
}
