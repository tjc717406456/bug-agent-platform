package com.tjc.bugagent.analysis;

/**
 * Analysis result returned to the UI.
 */
public class AnalysisResult {
    private Long id;
    private String plainAnswer;
    private String conclusion;
    private String confidence;
    private String evidenceJson;
    private String autoVerify;
    // 本次分析消耗的 token 总数，用于成本展示
    private int totalTokens;
    // 本次分析耗时(毫秒)
    private long elapsedMs;

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getAutoVerify() {
        return autoVerify;
    }

    public void setAutoVerify(String autoVerify) {
        this.autoVerify = autoVerify;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlainAnswer() {
        return plainAnswer;
    }

    public void setPlainAnswer(String plainAnswer) {
        this.plainAnswer = plainAnswer;
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
}
