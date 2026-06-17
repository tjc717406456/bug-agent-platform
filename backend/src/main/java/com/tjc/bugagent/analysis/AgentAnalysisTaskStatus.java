package com.tjc.bugagent.analysis;

/**
 * Agent 分析任务状态。
 */
public class AgentAnalysisTaskStatus {
    private String taskId;
    private String status;
    private String message;
    private AnalysisResult result;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public AnalysisResult getResult() {
        return result;
    }

    public void setResult(AnalysisResult result) {
        this.result = result;
    }
}
