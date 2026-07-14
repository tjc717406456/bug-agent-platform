package com.tjc.bugagent.analysis.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent Runner 执行期间的可变上下文。
 */
public class AgentRunContext {
    private final List<Map<String, Object>> messages;
    private final AgentToolExecutor.AgentToolContext toolContext;
    private final Map<String, Object> attributes = new HashMap<String, Object>();
    private final List<AgentToolEvent> toolEvents = new ArrayList<AgentToolEvent>();
    private int iteration;
    private int totalTokens;
    private boolean verificationEnabled;
    private boolean failed;
    private String finalContent;
    private AgentStopReason stopReason;

    public AgentRunContext(List<Map<String, Object>> messages, AgentToolExecutor.AgentToolContext toolContext,
                           boolean verificationEnabled) {
        this.messages = messages;
        this.toolContext = toolContext;
        this.verificationEnabled = verificationEnabled;
    }

    public AgentRunCheckpoint checkpoint(String phase, Map<String, Object> workflowState) {
        AgentRunCheckpoint checkpoint = new AgentRunCheckpoint();
        checkpoint.setIteration(iteration);
        checkpoint.setTotalTokens(totalTokens);
        checkpoint.setPhase(phase);
        checkpoint.setUpdatedAt(System.currentTimeMillis());
        checkpoint.setStopReason(stopReason == null ? null : stopReason.name());
        checkpoint.setVerificationEnabled(verificationEnabled);
        List<Map<String, Object>> messageSnapshot = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> message : messages) {
            messageSnapshot.add(new java.util.LinkedHashMap<String, Object>(message));
        }
        checkpoint.setMessages(messageSnapshot);
        checkpoint.setWorkflowState(workflowState);
        checkpoint.setCachedToolResults(toolContext.cachedResultsSnapshot());
        checkpoint.setToolEvents(new ArrayList<AgentToolEvent>(toolEvents));
        return checkpoint;
    }

    public void restore(AgentRunCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        totalTokens = checkpoint.getTotalTokens();
        verificationEnabled = checkpoint.isVerificationEnabled();
        toolEvents.clear();
        toolEvents.addAll(checkpoint.getToolEvents());
        toolContext.restoreCachedResults(checkpoint.getCachedToolResults());
    }

    public List<Map<String, Object>> getMessages() { return messages; }
    public AgentToolExecutor.AgentToolContext getToolContext() { return toolContext; }
    public Map<String, Object> getAttributes() { return attributes; }
    public List<AgentToolEvent> getToolEvents() { return toolEvents; }
    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }
    public int getTotalTokens() { return totalTokens; }
    public void addTokens(int tokens) { this.totalTokens += Math.max(0, tokens); }
    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean verificationEnabled) { this.verificationEnabled = verificationEnabled; }
    public boolean isFailed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }
    public String getFinalContent() { return finalContent; }
    public void setFinalContent(String finalContent) { this.finalContent = finalContent; }
    public AgentStopReason getStopReason() { return stopReason; }
    public void setStopReason(AgentStopReason stopReason) { this.stopReason = stopReason; }
}
