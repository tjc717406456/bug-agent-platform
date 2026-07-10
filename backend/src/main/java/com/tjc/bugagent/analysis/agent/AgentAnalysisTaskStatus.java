package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 分析任务状态。
 */
public class AgentAnalysisTaskStatus {
    // 进度步骤最多保留的条数，防止 Redis 里无限增长
    private static final int MAX_PROGRESS = 60;

    private String taskId;
    private String status;
    private String message;
    private AnalysisResult result;
    private List<String> progress = new ArrayList<String>();
    // 收口报告流式生成的累计快照，前端轮询期间渐进渲染；任务完成后清空（正式报告在 result 里）
    private String partialReport;
    // 提交任务的用户；轮询/停止时据此判权，顺带记录"这次分析是谁跑的"
    private Long ownerId;

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public List<String> getProgress() {
        return progress;
    }

    public void setProgress(List<String> progress) {
        this.progress = progress == null ? new ArrayList<String>() : progress;
    }

    /**
     * 追加一条进度，超出上限丢掉最早的。
     */
    public void addProgress(String step) {
        progress.add(step);
        while (progress.size() > MAX_PROGRESS) {
            progress.remove(0);
        }
    }

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

    public String getPartialReport() {
        return partialReport;
    }

    public void setPartialReport(String partialReport) {
        this.partialReport = partialReport;
    }
}
