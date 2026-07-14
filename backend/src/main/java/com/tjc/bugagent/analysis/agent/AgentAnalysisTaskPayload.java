package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisRequest;

/**
 * Agent 任务恢复负载，与前端可见状态分开存储。
 */
public class AgentAnalysisTaskPayload {
    private String type;
    private AnalysisRequest request;
    private Long recordId;
    private String question;

    public static AgentAnalysisTaskPayload analysis(AnalysisRequest request) {
        AgentAnalysisTaskPayload payload = new AgentAnalysisTaskPayload();
        payload.type = "ANALYSIS";
        payload.request = request;
        return payload;
    }

    public static AgentAnalysisTaskPayload explain(AnalysisRequest request) {
        AgentAnalysisTaskPayload payload = new AgentAnalysisTaskPayload();
        payload.type = "EXPLAIN";
        payload.request = request;
        return payload;
    }

    public static AgentAnalysisTaskPayload followUp(Long recordId, String question) {
        AgentAnalysisTaskPayload payload = new AgentAnalysisTaskPayload();
        payload.type = "FOLLOW_UP";
        payload.recordId = recordId;
        payload.question = question;
        return payload;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public AnalysisRequest getRequest() { return request; }
    public void setRequest(AnalysisRequest request) { this.request = request; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
}
