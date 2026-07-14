package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.auth.ProjectAccessGuard;
import com.tjc.bugagent.auth.UserContext;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 管理 Agent 异步分析任务。
 *
 * <p>submit* 方法跑在 servlet 线程里，登录上下文可用——所以在这里给任务盖上提交人的章；
 * taskRunner 的 @Async 执行体读不到上下文，也不需要读，它只按已授权的 projectId/recordId 干活。
 */
@Service
public class AgentAnalysisTaskService {
    private final AgentAnalysisTaskRunner taskRunner;
    private final AgentAnalysisTaskStore taskStore;
    private final AgentAnalysisCancelRegistry cancelRegistry;
    private final ProjectAccessGuard guard;

    public AgentAnalysisTaskService(AgentAnalysisTaskRunner taskRunner, AgentAnalysisTaskStore taskStore,
                                    AgentAnalysisCancelRegistry cancelRegistry, ProjectAccessGuard guard) {
        this.taskRunner = taskRunner;
        this.taskStore = taskStore;
        this.cancelRegistry = cancelRegistry;
        this.guard = guard;
    }

    /**
     * 提交异步分析任务。
     */
    public AgentAnalysisTaskSubmitResult submit(AnalysisRequest request) {
        AgentAnalysisTaskStatus status = newPendingTask("任务已提交");
        // 发起人随请求带进异步体：落库记 created_by，共享历史里能看出谁跑的
        request.setOwnerId(status.getOwnerId());
        taskStore.save(status);
        taskStore.savePayload(status.getTaskId(), AgentAnalysisTaskPayload.analysis(request));
        taskRunner.run(status.getTaskId(), request, status);
        return submitResult(status);
    }

    /**
     * 提交异步接口讲解任务，复用同一套任务存储和轮询。
     */
    public AgentAnalysisTaskSubmitResult submitExplain(AnalysisRequest request) {
        AgentAnalysisTaskStatus status = newPendingTask("任务已提交");
        request.setOwnerId(status.getOwnerId());
        taskStore.save(status);
        taskStore.savePayload(status.getTaskId(), AgentAnalysisTaskPayload.explain(request));
        taskRunner.runExplain(status.getTaskId(), request, status);
        return submitResult(status);
    }

    /**
     * 提交异步追问任务：基于已落库的分析记录继续提问，复用同一套任务存储和轮询。
     */
    public AgentAnalysisTaskSubmitResult submitFollowUp(Long recordId, String question) {
        AgentAnalysisTaskStatus status = newPendingTask("追问已提交");
        taskStore.save(status);
        taskStore.savePayload(status.getTaskId(), AgentAnalysisTaskPayload.followUp(recordId, question));
        taskRunner.runFollowUp(status.getTaskId(), recordId, question, status);
        return submitResult(status);
    }

    /**
     * 请求停止任务：打上取消标记，正在跑的循环会在下一轮间隙中断并置 CANCELLED。
     */
    public void requestStop(String taskId) {
        guard.assertTaskOwned(loadOwnerId(taskId));
        cancelRegistry.requestStop(taskId);
    }

    /**
     * 查询任务状态。taskId 虽是随机 UUID，仍按提交人判权，别把不可猜当访问控制。
     */
    public AgentAnalysisTaskStatus getStatus(String taskId) {
        AgentAnalysisTaskStatus status = taskStore.findAndRepair(taskId);
        if (status == null) {
            AgentAnalysisTaskStatus missing = new AgentAnalysisTaskStatus();
            missing.setTaskId(taskId);
            missing.setStatus("NOT_FOUND");
            missing.setMessage("任务不存在或已过期");
            return missing;
        }
        guard.assertTaskOwned(status.getOwnerId());
        return status;
    }

    /** 从最近完整轮次检查点恢复被中断或失败的任务。 */
    public synchronized AgentAnalysisTaskSubmitResult resume(String taskId) {
        AgentAnalysisTaskStatus status = taskStore.findAndRepair(taskId);
        guard.assertTaskOwned(status == null ? null : status.getOwnerId());
        if (status == null || !("INTERRUPTED".equals(status.getStatus()) || "FAILED".equals(status.getStatus()))) {
            throw new IllegalStateException("只有中断或失败的任务可以恢复");
        }
        if (status.getCheckpoint() != null && "COMPLETED".equals(status.getCheckpoint().getPhase())) {
            throw new IllegalStateException("任务已完成到收口阶段，为避免重复落库请重新提交");
        }
        AgentAnalysisTaskPayload payload = taskStore.findPayload(taskId);
        if (payload == null) {
            throw new IllegalStateException("任务恢复数据已过期，请重新提交");
        }
        status.setStatus("PENDING");
        status.setMessage("正在从第 " + (status.getCheckpoint() == null ? 0 : status.getCheckpoint().getIteration()) + " 轮恢复");
        status.setStopReason(null);
        taskStore.save(status);
        if ("ANALYSIS".equals(payload.getType())) {
            taskRunner.run(taskId, payload.getRequest(), status);
        } else if ("EXPLAIN".equals(payload.getType())) {
            taskRunner.runExplain(taskId, payload.getRequest(), status);
        } else if ("FOLLOW_UP".equals(payload.getType())) {
            taskRunner.runFollowUp(taskId, payload.getRecordId(), payload.getQuestion(), status);
        } else {
            throw new IllegalStateException("不支持恢复的任务类型: " + payload.getType());
        }
        return submitResult(status);
    }

    /** 任务不存在时返回 null，交给 assertTaskOwned 判成越权，避免用状态码差异探测他人任务。 */
    private Long loadOwnerId(String taskId) {
        AgentAnalysisTaskStatus status = taskStore.findAndRepair(taskId);
        return status == null ? null : status.getOwnerId();
    }

    private AgentAnalysisTaskStatus newPendingTask(String message) {
        AgentAnalysisTaskStatus status = new AgentAnalysisTaskStatus();
        status.setTaskId(UUID.randomUUID().toString());
        status.setStatus("PENDING");
        status.setMessage(message);
        status.setOwnerId(UserContext.currentUserId());
        status.setHeartbeatAt(System.currentTimeMillis());
        return status;
    }

    private AgentAnalysisTaskSubmitResult submitResult(AgentAnalysisTaskStatus status) {
        AgentAnalysisTaskSubmitResult result = new AgentAnalysisTaskSubmitResult();
        result.setTaskId(status.getTaskId());
        result.setStatus(status.getStatus());
        return result;
    }
}
