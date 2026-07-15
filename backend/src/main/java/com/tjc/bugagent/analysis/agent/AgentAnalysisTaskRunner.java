package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.AnalysisResult;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.DatasourceSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Agent 异步分析任务。
 */
@Service
public class AgentAnalysisTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisTaskRunner.class);

    private final AgentAnalysisService agentAnalysisService;
    private final ApiExplainService apiExplainService;
    private final FollowUpService followUpService;
    private final AgentAnalysisTaskStore taskStore;
    private final AgentAnalysisCancelRegistry cancelRegistry;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-task-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AgentAnalysisTaskRunner(AgentAnalysisService agentAnalysisService, ApiExplainService apiExplainService,
                                   FollowUpService followUpService,
                                   AgentAnalysisTaskStore taskStore, AgentAnalysisCancelRegistry cancelRegistry) {
        this.agentAnalysisService = agentAnalysisService;
        this.apiExplainService = apiExplainService;
        this.followUpService = followUpService;
        this.taskStore = taskStore;
        this.cancelRegistry = cancelRegistry;
    }

    /**
     * 进度回调 + 取消信号：每步写回任务状态供前端轮询；isCancelled 查取消标记，让循环能在轮间停下。
     */
    private AnalysisProgressListener listener(String taskId, AgentAnalysisTaskStatus status) {
        return new AnalysisProgressListener() {
            @Override
            public void onStep(String step) {
                status.addProgress(step);
                taskStore.save(status);
            }

            @Override
            public boolean isCancelled() {
                return cancelRegistry.isStopRequested(taskId);
            }

            @Override
            public void onPartialReport(String partial) {
                // 快照已在编排层节流(800ms)，这里直接回写供前端轮询取
                status.setPartialReport(partial);
                taskStore.save(status);
            }

            @Override
            public void onCheckpoint(AgentRunCheckpoint checkpoint) {
                status.setCheckpoint(checkpoint);
                status.setStopReason(checkpoint.getStopReason());
                taskStore.save(status);
            }

            @Override
            public ProjectExecutionScope executionScope(Long projectId, Long versionId, ProjectDatasource datasource) {
                return ProjectExecutionScope.create(taskId, status.getOwnerId(), projectId, versionId, datasource);
            }

            @Override
            public ProjectExecutionScope executionScope(Long projectId, Long versionId, DatasourceSelection selection) {
                return ProjectExecutionScope.create(taskId, status.getOwnerId(), projectId, versionId, selection);
            }

            @Override
            public AgentRunCheckpoint resumeCheckpoint() {
                return status.getCheckpoint();
            }
        };
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    private ScheduledFuture<?> startHeartbeat(AgentAnalysisTaskStatus status) {
        return heartbeatExecutor.scheduleAtFixedRate(() -> taskStore.touch(status.getTaskId()), 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 后台运行分析，避免 HTTP 请求线程长期占用。每次状态变更都回写存储。
     */
    @Async("agentAnalysisExecutor")
    public void run(String taskId, AnalysisRequest request, AgentAnalysisTaskStatus status) {
        status.setStatus("RUNNING");
        status.setMessage("Agent 正在分析");
        taskStore.save(status);
        ScheduledFuture<?> heartbeat = startHeartbeat(status);
        try {
            AnalysisResult result = agentAnalysisService.analyze(request, listener(taskId, status));
            status.setResult(result);
            // 正式报告已在 result 里，流式半成品清掉，别让 Redis 存两份
            status.setPartialReport(null);
            status.setStatus("SUCCESS");
            if (status.getStopReason() == null) {
                status.setStopReason(AgentStopReason.FINISH_TOOL.name());
            }
            status.setMessage("分析完成");
        } catch (AnalysisCancelledException cancelled) {
            status.setStatus("CANCELLED");
            status.setStopReason(AgentStopReason.CANCELLED.name());
            status.setMessage("已手动停止");
        } catch (Exception exception) {
            log.error("agent analysis task failed, taskId={}", taskId, exception);
            status.setStatus("FAILED");
            status.setStopReason(AgentStopReason.INTERNAL_ERROR.name());
            status.setMessage(exception.getMessage());
        } finally {
            heartbeat.cancel(false);
            cancelRegistry.clear(taskId);
        }
        taskStore.save(status);
    }

    /**
     * 后台运行追问：基于已落库的分析记录回答追加提问，复用同一套任务状态/轮询/流式快照。
     */
    @Async("agentAnalysisExecutor")
    public void runFollowUp(String taskId, Long recordId, String question, AgentAnalysisTaskStatus status) {
        status.setStatus("RUNNING");
        status.setMessage("正在回答追问");
        taskStore.save(status);
        ScheduledFuture<?> heartbeat = startHeartbeat(status);
        try {
            AnalysisResult result = followUpService.answer(recordId, question, listener(taskId, status));
            status.setResult(result);
            status.setPartialReport(null);
            status.setStatus("SUCCESS");
            if (status.getStopReason() == null) {
                status.setStopReason(AgentStopReason.FINISH_TOOL.name());
            }
            status.setMessage("追问已回答");
        } catch (AnalysisCancelledException cancelled) {
            status.setStatus("CANCELLED");
            status.setStopReason(AgentStopReason.CANCELLED.name());
            status.setMessage("已手动停止");
        } catch (Exception exception) {
            log.error("follow-up task failed, taskId={} recordId={}", taskId, recordId, exception);
            status.setStatus("FAILED");
            status.setStopReason(AgentStopReason.INTERNAL_ERROR.name());
            status.setMessage(exception.getMessage());
        } finally {
            heartbeat.cancel(false);
            cancelRegistry.clear(taskId);
        }
        taskStore.save(status);
    }

    /**
     * 后台运行接口讲解，复用同一套任务状态与进度回写，前端轮询同一个 poll 接口。
     */
    @Async("agentAnalysisExecutor")
    public void runExplain(String taskId, AnalysisRequest request, AgentAnalysisTaskStatus status) {
        status.setStatus("RUNNING");
        status.setMessage("正在讲解接口");
        taskStore.save(status);
        ScheduledFuture<?> heartbeat = startHeartbeat(status);
        try {
            AnalysisResult result = apiExplainService.explain(request, listener(taskId, status));
            status.setResult(result);
            // 正式讲解已在 result 里，流式半成品清掉
            status.setPartialReport(null);
            status.setStatus("SUCCESS");
            if (status.getStopReason() == null) {
                status.setStopReason(AgentStopReason.FINISH_TOOL.name());
            }
            status.setMessage("讲解完成");
        } catch (AnalysisCancelledException cancelled) {
            status.setStatus("CANCELLED");
            status.setStopReason(AgentStopReason.CANCELLED.name());
            status.setMessage("已手动停止");
        } catch (Exception exception) {
            log.error("api explain task failed, taskId={}", taskId, exception);
            status.setStatus("FAILED");
            status.setStopReason(AgentStopReason.INTERNAL_ERROR.name());
            status.setMessage(exception.getMessage());
        } finally {
            heartbeat.cancel(false);
            cancelRegistry.clear(taskId);
        }
        taskStore.save(status);
    }
}
