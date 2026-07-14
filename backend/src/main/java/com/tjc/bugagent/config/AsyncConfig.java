package com.tjc.bugagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 异步分析专用线程池，替代 Spring 默认的 SimpleAsyncTaskExecutor（不复用线程、无上限）。
 */
@Configuration
public class AsyncConfig {
    private final AppProperties appProperties;

    public AsyncConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean("agentAnalysisExecutor")
    public Executor agentAnalysisExecutor() {
        AppProperties.Async async = appProperties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix("agent-analysis-");
        // 队列与线程都满时，由提交线程兜底执行，避免任务被直接丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 源码导入专用线程池，避免 ZIP 解压和代码索引占用 Agent 分析线程。
     */
    @Bean("sourceImportExecutor")
    public Executor sourceImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("source-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
