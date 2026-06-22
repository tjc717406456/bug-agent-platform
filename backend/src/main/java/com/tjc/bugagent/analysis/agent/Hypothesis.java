package com.tjc.bugagent.analysis.agent;

/**
 * 侦察轮产出的候选根因：一句根因描述 + 0-100 的置信分，供 AUTO 判歧义和并行验证用。
 */
public class Hypothesis {
    private String cause;
    private int score;

    public Hypothesis() {
    }

    public Hypothesis(String cause, int score) {
        this.cause = cause;
        this.score = score;
    }

    public String getCause() {
        return cause;
    }

    public void setCause(String cause) {
        this.cause = cause;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }
}
