package com.tjc.bugagent.analysis.agent;

/**
 * Workflow 对 Runner 的本轮控制指令。
 */
public class AgentRunDirective {
    private final boolean stop;
    private final String finalContent;
    private final AgentStopReason stopReason;

    private AgentRunDirective(boolean stop, String finalContent, AgentStopReason stopReason) {
        this.stop = stop;
        this.finalContent = finalContent;
        this.stopReason = stopReason;
    }

    public static AgentRunDirective continueRun() { return new AgentRunDirective(false, null, null); }
    public static AgentRunDirective stop(String content, AgentStopReason reason) { return new AgentRunDirective(true, content, reason); }
    public boolean isStop() { return stop; }
    public String getFinalContent() { return finalContent; }
    public AgentStopReason getStopReason() { return stopReason; }
}
