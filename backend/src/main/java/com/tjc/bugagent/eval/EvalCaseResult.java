package com.tjc.bugagent.eval;

import java.util.List;

/**
 * 单条用例的评估结果。
 */
public class EvalCaseResult {
    private String name;
    private boolean passed;
    private int matchedKeywords;
    private int totalKeywords;
    private double matchRatio;
    private List<String> missedKeywords;
    private String confidence;
    private String expectConfidence;
    private boolean confidenceOk;
    private String plainAnswer;
    private long elapsedMs;
    private String errorMessage;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public int getMatchedKeywords() {
        return matchedKeywords;
    }

    public void setMatchedKeywords(int matchedKeywords) {
        this.matchedKeywords = matchedKeywords;
    }

    public int getTotalKeywords() {
        return totalKeywords;
    }

    public void setTotalKeywords(int totalKeywords) {
        this.totalKeywords = totalKeywords;
    }

    public double getMatchRatio() {
        return matchRatio;
    }

    public void setMatchRatio(double matchRatio) {
        this.matchRatio = matchRatio;
    }

    public List<String> getMissedKeywords() {
        return missedKeywords;
    }

    public void setMissedKeywords(List<String> missedKeywords) {
        this.missedKeywords = missedKeywords;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getExpectConfidence() {
        return expectConfidence;
    }

    public void setExpectConfidence(String expectConfidence) {
        this.expectConfidence = expectConfidence;
    }

    public boolean isConfidenceOk() {
        return confidenceOk;
    }

    public void setConfidenceOk(boolean confidenceOk) {
        this.confidenceOk = confidenceOk;
    }

    public String getPlainAnswer() {
        return plainAnswer;
    }

    public void setPlainAnswer(String plainAnswer) {
        this.plainAnswer = plainAnswer;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
