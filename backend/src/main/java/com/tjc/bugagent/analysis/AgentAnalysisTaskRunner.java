package com.tjc.bugagent.analysis;

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
    private final AgentAnalysisTaskStore taskStore;

    public AgentAnalysisTaskRunner(AgentAnalysisService agentAnalysisService, AgentAnalysisTaskStore taskStore) {
        this.agentAnalysisService = agentAnalysisService;
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
            AnalysisResult result = agentAnalysisService.analyze(request);
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
}
