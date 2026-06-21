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
