package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 管理 Agent 异步分析任务。
 */
@Service
public class AgentAnalysisTaskService {
    private final AgentAnalysisTaskRunner taskRunner;
    private final AgentAnalysisTaskStore taskStore;

    public AgentAnalysisTaskService(AgentAnalysisTaskRunner taskRunner, AgentAnalysisTaskStore taskStore) {
        this.taskRunner = taskRunner;
        this.taskStore = taskStore;
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
        taskStore.save(status);
        taskRunner.run(taskId, request, status);

        AgentAnalysisTaskSubmitResult result = new AgentAnalysisTaskSubmitResult();
        result.setTaskId(taskId);
        result.setStatus(status.getStatus());
        return result;
    }

    /**
     * 提交异步接口讲解任务，复用同一套任务存储和轮询。
     */
    public AgentAnalysisTaskSubmitResult submitExplain(AnalysisRequest request) {
        String taskId = UUID.randomUUID().toString();
        AgentAnalysisTaskStatus status = new AgentAnalysisTaskStatus();
        status.setTaskId(taskId);
        status.setStatus("PENDING");
        status.setMessage("任务已提交");
        taskStore.save(status);
        taskRunner.runExplain(taskId, request, status);

        AgentAnalysisTaskSubmitResult result = new AgentAnalysisTaskSubmitResult();
        result.setTaskId(taskId);
        result.setStatus(status.getStatus());
        return result;
    }

    /**
     * 查询任务状态。
     */
    public AgentAnalysisTaskStatus getStatus(String taskId) {
        AgentAnalysisTaskStatus status = taskStore.find(taskId);
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
