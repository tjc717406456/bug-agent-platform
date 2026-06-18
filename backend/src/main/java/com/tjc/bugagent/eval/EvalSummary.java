package com.tjc.bugagent.eval;

import java.util.List;

/**
 * 一次评估跑批的汇总：整体准确率 + 每条用例明细。
 */
public class EvalSummary {
    private int total;
    private int passed;
    private double accuracy;
    private double avgMatchRatio;
    private double confidenceHitRate;
    private long totalElapsedMs;
    private List<EvalCaseResult> results;

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
    }

    public double getAvgMatchRatio() {
        return avgMatchRatio;
    }

    public void setAvgMatchRatio(double avgMatchRatio) {
        this.avgMatchRatio = avgMatchRatio;
    }

    public double getConfidenceHitRate() {
        return confidenceHitRate;
    }

    public void setConfidenceHitRate(double confidenceHitRate) {
        this.confidenceHitRate = confidenceHitRate;
    }

    public long getTotalElapsedMs() {
        return totalElapsedMs;
    }

    public void setTotalElapsedMs(long totalElapsedMs) {
        this.totalElapsedMs = totalElapsedMs;
    }

    public List<EvalCaseResult> getResults() {
        return results;
    }

    public void setResults(List<EvalCaseResult> results) {
        this.results = results;
    }
}
