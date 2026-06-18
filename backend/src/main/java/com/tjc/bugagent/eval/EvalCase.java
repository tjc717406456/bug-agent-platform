package com.tjc.bugagent.eval;

import java.util.List;

/**
 * 一条标注好的 Bug 评估用例：分析输入 + 期望命中的根因关键词和置信度。
 * 用例集是衡量分析准确率的尺子，改 prompt/逻辑后跑一遍就知道变好还是变差。
 */
public class EvalCase {
    private String name;
    private Long projectId;
    private Long versionId;
    private String apiPath;
    private String userDescription;
    private String requestBody;
    private String responseBody;
    private String stackTrace;
    private String traceId;
    private String requestTime;
    // 期望结论里出现的关键词，全部命中才算定位准（如 ["nick_name", "字段", "不存在"]）
    private List<String> expectKeywords;
    // 期望的最低置信度，可选（LOW/MEDIUM/HIGH）
    private String expectConfidence;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public List<String> getExpectKeywords() {
        return expectKeywords;
    }

    public void setExpectKeywords(List<String> expectKeywords) {
        this.expectKeywords = expectKeywords;
    }

    public String getExpectConfidence() {
        return expectConfidence;
    }

    public void setExpectConfidence(String expectConfidence) {
        this.expectConfidence = expectConfidence;
    }
}
