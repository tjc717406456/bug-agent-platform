package com.tjc.bugagent.analysis;

/**
 * 一条分析历史记录。列表用精简字段，详情带完整结论与证据。
 */
public class AnalysisRecord {
    private Long id;
    private Long projectId;
    private String apiPath;
    // ANALYSIS bug分析 / EXPLAIN 接口讲解
    private String recordType;
    private String userDescription;
    private String conclusion;
    private String confidence;
    private String evidenceJson;
    private String autoVerify;
    private String feedbackVerdict;
    private String actualRootCause;
    private String expectKeywords;
    private String createdByName;
    private String createdAt;
    private String environment;
    private String databaseAccessLevel;
    private Long schemaDatasourceId;
    private Long businessDatasourceId;

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getDatabaseAccessLevel() { return databaseAccessLevel; }
    public void setDatabaseAccessLevel(String databaseAccessLevel) { this.databaseAccessLevel = databaseAccessLevel; }
    public Long getSchemaDatasourceId() { return schemaDatasourceId; }
    public void setSchemaDatasourceId(Long schemaDatasourceId) { this.schemaDatasourceId = schemaDatasourceId; }
    public Long getBusinessDatasourceId() { return businessDatasourceId; }
    public void setBusinessDatasourceId(Long businessDatasourceId) { this.businessDatasourceId = businessDatasourceId; }

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

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
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

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
