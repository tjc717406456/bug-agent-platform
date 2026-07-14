package com.tjc.bugagent.analysis.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 每个完整轮次结束后的可持久化检查点。
 */
public class AgentRunCheckpoint {
    private int iteration;
    private int totalTokens;
    private String phase;
    private long updatedAt;
    private String stopReason;
    private boolean verificationEnabled;
    private List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
    private Map<String, Object> workflowState = new LinkedHashMap<String, Object>();
    private Map<String, AgentToolResult> cachedToolResults = new LinkedHashMap<String, AgentToolResult>();
    private List<AgentToolEvent> toolEvents = new ArrayList<AgentToolEvent>();

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean verificationEnabled) { this.verificationEnabled = verificationEnabled; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages == null ? new ArrayList<Map<String, Object>>() : messages;
    }
    public Map<String, Object> getWorkflowState() { return workflowState; }
    public void setWorkflowState(Map<String, Object> workflowState) {
        this.workflowState = workflowState == null ? new LinkedHashMap<String, Object>() : workflowState;
    }
    public Map<String, AgentToolResult> getCachedToolResults() { return cachedToolResults; }
    public void setCachedToolResults(Map<String, AgentToolResult> cachedToolResults) {
        this.cachedToolResults = cachedToolResults == null
                ? new LinkedHashMap<String, AgentToolResult>() : cachedToolResults;
    }
    public List<AgentToolEvent> getToolEvents() { return toolEvents; }
    public void setToolEvents(List<AgentToolEvent> toolEvents) {
        this.toolEvents = toolEvents == null ? new ArrayList<AgentToolEvent>() : toolEvents;
    }
}
