package com.tjc.bugagent.analysis.agent;

/**
 * Agent 工具执行后的统一结果。
 */
public class AgentToolResult {
    private boolean ok;
    private String tool;
    private String summary;
    private String evidence;

    /**
     * 创建成功结果。
     */
    public static AgentToolResult ok(String tool, String summary, String evidence) {
        AgentToolResult result = new AgentToolResult();
        result.ok = true;
        result.tool = tool;
        result.summary = summary;
        result.evidence = evidence;
        return result;
    }

    /**
     * 创建失败结果。
     */
    public static AgentToolResult fail(String tool, String summary) {
        AgentToolResult result = new AgentToolResult();
        result.ok = false;
        result.tool = tool;
        result.summary = summary;
        result.evidence = summary;
        return result;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }
}
