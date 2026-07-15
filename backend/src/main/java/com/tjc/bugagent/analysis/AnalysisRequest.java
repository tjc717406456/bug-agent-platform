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
    private String logText;
    private String logId;
    // 问题实际发生环境；未传时使用项目配置的第一个环境
    private String environment;
    // 用户选择的策略：AUTO/NONE/SCHEMA_ONLY/BUSINESS_DATA
    private String databasePolicy = "AUTO";
    // 后端解析后的实际权限和数据源，仅服务端写入
    private String databaseAccessLevel;
    private Long schemaDatasourceId;
    private Long businessDatasourceId;
    // 本次是否强制走深度模式（多假设并行）；null 时按全局 hypothesis-mode 配置
    private Boolean deepMode;
    // 本次是否启用多 Agent 分工调查；与 deepMode 独立，二者同时开启时优先多 Agent，避免并行套娃
    private Boolean multiAgentMode;
    // 发起人，提交时在 servlet 线程从登录上下文盖章（异步执行体读不到），客户端传值会被覆盖
    private Long ownerId;

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getDatabasePolicy() { return databasePolicy; }
    public void setDatabasePolicy(String databasePolicy) { this.databasePolicy = databasePolicy; }
    public String getDatabaseAccessLevel() { return databaseAccessLevel; }
    public void setDatabaseAccessLevel(String databaseAccessLevel) { this.databaseAccessLevel = databaseAccessLevel; }
    public Long getSchemaDatasourceId() { return schemaDatasourceId; }
    public void setSchemaDatasourceId(Long schemaDatasourceId) { this.schemaDatasourceId = schemaDatasourceId; }
    public Long getBusinessDatasourceId() { return businessDatasourceId; }
    public void setBusinessDatasourceId(Long businessDatasourceId) { this.businessDatasourceId = businessDatasourceId; }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Boolean getDeepMode() {
        return deepMode;
    }

    public void setDeepMode(Boolean deepMode) {
        this.deepMode = deepMode;
    }

    public Boolean getMultiAgentMode() {
        return multiAgentMode;
    }

    public void setMultiAgentMode(Boolean multiAgentMode) {
        this.multiAgentMode = multiAgentMode;
    }

    public String getLogText() {
        return logText;
    }

    public void setLogText(String logText) {
        this.logText = logText;
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
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
}
