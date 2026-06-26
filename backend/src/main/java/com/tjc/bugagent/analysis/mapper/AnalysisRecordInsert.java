package com.tjc.bugagent.analysis.mapper;

/**
 * analysis_record 写入参数载体。insert 后自增主键由 MyBatis 回填到 id（useGeneratedKeys）。
 */
public class AnalysisRecordInsert {
    private Long id;
    private Long projectId;
    private Long versionId;
    private String apiPath;
    private String userDescription;
    private String requestBody;
    private String responseBody;
    private String stackTrace;
    private String screenshotPaths;
    private String traceId;
    private String requestTime;
    private String conclusion;
    private String confidence;
    private String evidenceJson;
    private String autoVerify;
    private String autoVerifyKeywords;
    private int roundsCount;
    private int totalTokens;

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

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getAutoVerify() {
        return autoVerify;
    }

    public void setAutoVerify(String autoVerify) {
        this.autoVerify = autoVerify;
    }

    public String getAutoVerifyKeywords() {
        return autoVerifyKeywords;
    }

    public void setAutoVerifyKeywords(String autoVerifyKeywords) {
        this.autoVerifyKeywords = autoVerifyKeywords;
    }

    public int getRoundsCount() {
        return roundsCount;
    }

    public void setRoundsCount(int roundsCount) {
        this.roundsCount = roundsCount;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
