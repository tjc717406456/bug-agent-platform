package com.tjc.bugagent.analysis;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 执行 Agent 异步分析任务。
 */
@Service
public class AgentAnalysisTaskRunner {
    private final AgentAnalysisService agentAnalysisService;

    public AgentAnalysisTaskRunner(AgentAnalysisService agentAnalysisService) {
        this.agentAnalysisService = agentAnalysisService;
    }

    /**
     * 后台运行分析，避免 HTTP 请求线程长期占用。
     */
    @Async
    public void run(String taskId, AnalysisRequest request, AgentAnalysisTaskStatus status) {
        status.setStatus("RUNNING");
        status.setMessage("Agent 正在分析");
        try {
            AnalysisResult result = agentAnalysisService.analyze(request);
            status.setResult(result);
            status.setStatus("SUCCESS");
            status.setMessage("分析完成");
        } catch (Exception exception) {
            status.setStatus("FAILED");
            status.setMessage(exception.getMessage());
        }
    }
}
