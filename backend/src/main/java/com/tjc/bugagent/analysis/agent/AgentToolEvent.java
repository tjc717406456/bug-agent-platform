package com.tjc.bugagent.analysis.agent;

/**
 * 一次工具执行的结构化轨迹。
 */
public class AgentToolEvent {
    private int iteration;
    private String tool;
    private String status;
    private String summary;
    private long elapsedMs;

    public AgentToolEvent() {
    }

    public AgentToolEvent(int iteration, String tool, String status, String summary, long elapsedMs) {
        this.iteration = iteration;
        this.tool = tool;
        this.status = status;
        this.summary = summary;
        this.elapsedMs = elapsedMs;
    }

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
}
