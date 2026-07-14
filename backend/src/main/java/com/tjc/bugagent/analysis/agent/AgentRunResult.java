package com.tjc.bugagent.analysis.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent Runner 的结构化运行结果。
 */
public class AgentRunResult {
    private final String finalContent;
    private final List<Map<String, Object>> messages;
    private final int iterations;
    private final int totalTokens;
    private final boolean failed;
    private final AgentStopReason stopReason;
    private final List<AgentToolEvent> toolEvents;

    public AgentRunResult(AgentRunContext context) {
        this.finalContent = context.getFinalContent();
        this.messages = context.getMessages();
        this.iterations = context.getIteration();
        this.totalTokens = context.getTotalTokens();
        this.failed = context.isFailed();
        this.stopReason = context.getStopReason();
        this.toolEvents = context.getToolEvents();
    }

    public String getFinalContent() { return finalContent; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public int getIterations() { return iterations; }
    public int getTotalTokens() { return totalTokens; }
    public boolean isFailed() { return failed; }
    public AgentStopReason getStopReason() { return stopReason; }
    public List<AgentToolEvent> getToolEvents() { return toolEvents; }
}
