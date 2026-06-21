package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 单轮内多个查证工具的并行执行器：自带小型线程池（非 Spring @Async，避免抢占分析任务线程池），
 * 单工具直接执行省线程开销，多工具并发并对每个设超时，避免一个卡死的工具拖垮整轮分析。
 */
@Component
public class ToolFanoutExecutor {

    private final AgentToolExecutor agentToolExecutor;
    private final AppProperties appProperties;
    private final ExecutorService toolFanoutPool;

    public ToolFanoutExecutor(AgentToolExecutor agentToolExecutor, AppProperties appProperties) {
        this.agentToolExecutor = agentToolExecutor;
        this.appProperties = appProperties;
        int poolSize = Math.max(1, appProperties.getAgent().getToolFanoutPoolSize());
        this.toolFanoutPool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "agent-tool-fanout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        toolFanoutPool.shutdownNow();
    }

    /**
     * 并行执行本轮的查证工具；单个工具时直接执行避免线程开销。
     */
    public List<AgentToolResult> executeAll(List<AgentToolCall> calls, AgentToolExecutor.AgentToolContext context) {
        if (calls.size() == 1) {
            List<AgentToolResult> single = new ArrayList<AgentToolResult>(1);
            single.add(safeExecute(calls.get(0), context));
            return single;
        }
        int timeoutSeconds = Math.max(1, appProperties.getAgent().getToolTimeoutSeconds());
        List<CompletableFuture<AgentToolResult>> futures = new ArrayList<CompletableFuture<AgentToolResult>>();
        for (AgentToolCall call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> safeExecute(call, context), toolFanoutPool));
        }
        List<AgentToolResult> results = new ArrayList<AgentToolResult>(calls.size());
        for (int index = 0; index < futures.size(); index++) {
            CompletableFuture<AgentToolResult> future = futures.get(index);
            String action = AgentTextUtils.safe(calls.get(index).getAction());
            try {
                // 单工具超时直接判失败并打断，避免一个卡死的工具拖垮整轮分析
                results.add(future.get(timeoutSeconds, TimeUnit.SECONDS));
            } catch (TimeoutException timeout) {
                future.cancel(true);
                results.add(AgentToolResult.fail(action, "工具执行超时(" + timeoutSeconds + "s)"));
            } catch (Exception exception) {
                results.add(AgentToolResult.fail(action, "工具执行异常: " + exception.getMessage()));
            }
        }
        return results;
    }

    private AgentToolResult safeExecute(AgentToolCall call, AgentToolExecutor.AgentToolContext context) {
        try {
            return agentToolExecutor.execute(call, context);
        } catch (Exception exception) {
            return AgentToolResult.fail(AgentTextUtils.safe(call.getAction()), "工具执行异常: " + exception.getMessage());
        }
    }
}
