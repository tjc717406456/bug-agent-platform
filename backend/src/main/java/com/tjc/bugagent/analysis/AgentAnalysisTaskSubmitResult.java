package com.tjc.bugagent.analysis;

/**
 * Agent 分析任务提交结果。
 */
public class AgentAnalysisTaskSubmitResult {
    private String taskId;
    private String status;

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
}
