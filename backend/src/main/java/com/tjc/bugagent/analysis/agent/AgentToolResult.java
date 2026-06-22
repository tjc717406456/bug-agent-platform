package com.tjc.bugagent.analysis.agent;

/**
 * Agent 工具执行后的统一结果。
 */
public class AgentToolResult {
    private boolean ok;
    // 真错误(参数缺失/数据源没配/工具异常)才置 true；"查了没找到"这类空结果为 false，不算硬失败
    private boolean hardFailure;
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
     * 创建失败结果（真错误：参数问题、数据源缺失、工具异常等，计入连续失败掐断）。
     */
    public static AgentToolResult fail(String tool, String summary) {
        AgentToolResult result = new AgentToolResult();
        result.ok = false;
        result.hardFailure = true;
        result.tool = tool;
        result.summary = summary;
        result.evidence = summary;
        return result;
    }

    /**
     * 创建空结果（查找类工具查了但没命中）。是正常探索的一步，不算硬失败、不计入掐断。
     */
    public static AgentToolResult empty(String tool, String summary) {
        AgentToolResult result = new AgentToolResult();
        result.ok = false;
        result.hardFailure = false;
        result.tool = tool;
        result.summary = summary;
        result.evidence = summary;
        return result;
    }

    public boolean isOk() {
        return ok;
    }

    /** 是否真错误。空结果(查无命中)返回 false。 */
    public boolean isHardFailure() {
        return hardFailure;
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
