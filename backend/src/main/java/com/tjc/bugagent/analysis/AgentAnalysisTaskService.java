package com.tjc.bugagent.analysis;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 Agent 异步分析任务。
 */
@Service
public class AgentAnalysisTaskService {
    private final AgentAnalysisTaskRunner taskRunner;
    private final Map<String, AgentAnalysisTaskStatus> tasks = new ConcurrentHashMap<String, AgentAnalysisTaskStatus>();

    public AgentAnalysisTaskService(AgentAnalysisTaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    /**
     * 提交异步分析任务。
     */
    public AgentAnalysisTaskSubmitResult submit(AnalysisRequest request) {
        String taskId = UUID.randomUUID().toString();
        AgentAnalysisTaskStatus status = new AgentAnalysisTaskStatus();
        status.setTaskId(taskId);
        status.setStatus("PENDING");
        status.setMessage("任务已提交");
        tasks.put(taskId, status);
        taskRunner.run(taskId, request, status);

        AgentAnalysisTaskSubmitResult result = new AgentAnalysisTaskSubmitResult();
        result.setTaskId(taskId);
        result.setStatus(status.getStatus());
        return result;
    }

    /**
     * 查询任务状态。
     */
    public AgentAnalysisTaskStatus getStatus(String taskId) {
        AgentAnalysisTaskStatus status = tasks.get(taskId);
        if (status == null) {
            AgentAnalysisTaskStatus missing = new AgentAnalysisTaskStatus();
            missing.setTaskId(taskId);
            missing.setStatus("NOT_FOUND");
            missing.setMessage("任务不存在或已过期");
            return missing;
        }
        return status;
    }
}
