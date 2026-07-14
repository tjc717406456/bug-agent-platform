package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent Runner 的运行参数。
 */
public class AgentRunSpec {
    public enum ModelRole { PRIMARY, UTILITY }

    private List<Map<String, Object>> messages;
    private AgentToolExecutor.AgentToolContext toolContext;
    private AgentRunPolicy policy;
    private AnalysisProgressListener progress = AnalysisProgressListener.NOOP;
    private List<AgentRunHook> hooks = new ArrayList<AgentRunHook>();
    private int maxIterations;
    private int keepRecentRounds;
    private boolean verificationEnabled;
    private ModelRole modelRole = ModelRole.PRIMARY;
    private String modelFailureMessage;

    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }
    public AgentToolExecutor.AgentToolContext getToolContext() { return toolContext; }
    public void setToolContext(AgentToolExecutor.AgentToolContext toolContext) { this.toolContext = toolContext; }
    public AgentRunPolicy getPolicy() { return policy; }
    public void setPolicy(AgentRunPolicy policy) { this.policy = policy; }
    public AnalysisProgressListener getProgress() { return progress; }
    public void setProgress(AnalysisProgressListener progress) { this.progress = progress; }
    public List<AgentRunHook> getHooks() { return hooks; }
    public void setHooks(List<AgentRunHook> hooks) { this.hooks = hooks; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getKeepRecentRounds() { return keepRecentRounds; }
    public void setKeepRecentRounds(int keepRecentRounds) { this.keepRecentRounds = keepRecentRounds; }
    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean verificationEnabled) { this.verificationEnabled = verificationEnabled; }
    public ModelRole getModelRole() { return modelRole; }
    public void setModelRole(ModelRole modelRole) { this.modelRole = modelRole; }
    public String getModelFailureMessage() { return modelFailureMessage; }
    public void setModelFailureMessage(String modelFailureMessage) { this.modelFailureMessage = modelFailureMessage; }
}
