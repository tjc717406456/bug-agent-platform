package com.tjc.bugagent.analysis;

/**
 * 一条分析历史记录。列表用精简字段，详情带完整结论与证据。
 */
public class AnalysisRecord {
    private Long id;
    private Long projectId;
    private String apiPath;
    private String userDescription;
    private String conclusion;
    private String confidence;
    private String evidenceJson;
    private String autoVerify;
    private String feedbackVerdict;
    private String actualRootCause;
    private String expectKeywords;
    private String createdAt;

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

    public String getFeedbackVerdict() {
        return feedbackVerdict;
    }

    public void setFeedbackVerdict(String feedbackVerdict) {
        this.feedbackVerdict = feedbackVerdict;
    }

    public String getActualRootCause() {
        return actualRootCause;
    }

    public void setActualRootCause(String actualRootCause) {
        this.actualRootCause = actualRootCause;
    }

    public String getExpectKeywords() {
        return expectKeywords;
    }

    public void setExpectKeywords(String expectKeywords) {
        this.expectKeywords = expectKeywords;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
