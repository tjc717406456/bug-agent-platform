package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 执行 Agent 异步分析任务。
 */
@Service
public class AgentAnalysisTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisTaskRunner.class);

    private final AgentAnalysisService agentAnalysisService;
    private final ApiExplainService apiExplainService;
    private final AgentAnalysisTaskStore taskStore;

    public AgentAnalysisTaskRunner(AgentAnalysisService agentAnalysisService, ApiExplainService apiExplainService,
                                   AgentAnalysisTaskStore taskStore) {
        this.agentAnalysisService = agentAnalysisService;
        this.apiExplainService = apiExplainService;
        this.taskStore = taskStore;
    }

    /**
     * 后台运行分析，避免 HTTP 请求线程长期占用。每次状态变更都回写存储。
     */
    @Async("agentAnalysisExecutor")
    public void run(String taskId, AnalysisRequest request, AgentAnalysisTaskStatus status) {
        status.setStatus("RUNNING");
        status.setMessage("Agent 正在分析");
        taskStore.save(status);
        try {
            // 每个进度步骤写回任务状态，前端轮询即可实时看到分析过程
            AnalysisResult result = agentAnalysisService.analyze(request, step -> {
                status.addProgress(step);
                taskStore.save(status);
            });
            status.setResult(result);
            status.setStatus("SUCCESS");
            status.setMessage("分析完成");
        } catch (Exception exception) {
            log.error("agent analysis task failed, taskId={}", taskId, exception);
            status.setStatus("FAILED");
            status.setMessage(exception.getMessage());
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
        try {
            AnalysisResult result = apiExplainService.explain(request, step -> {
                status.addProgress(step);
                taskStore.save(status);
            });
            status.setResult(result);
            status.setStatus("SUCCESS");
            status.setMessage("讲解完成");
        } catch (Exception exception) {
            log.error("api explain task failed, taskId={}", taskId, exception);
            status.setStatus("FAILED");
            status.setMessage(exception.getMessage());
        }
        taskStore.save(status);
    }
}
