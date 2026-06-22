package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 多假设分支的并行执行器：自带小线程池，把几条独立调查链并发跑完。
 * 池大小受控以免 N 条链同时锤模型撞限流；某条链抛异常只置空它一条，不连累其它分支。
 */
@Component
public class HypothesisFanoutExecutor {
    private static final Logger log = LoggerFactory.getLogger(HypothesisFanoutExecutor.class);

    private final ExecutorService branchPool;

    public HypothesisFanoutExecutor(AppProperties appProperties) {
        int poolSize = Math.max(1, appProperties.getAgent().getHypothesisPoolSize());
        this.branchPool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "agent-hypothesis-branch");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        branchPool.shutdownNow();
    }

    /**
     * 并行跑所有分支任务，按入参顺序返回结果；某条抛异常对应位置为 null，调用方过滤。
     */
    public <T> List<T> runAll(List<Supplier<T>> tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<CompletableFuture<T>>(tasks.size());
        for (Supplier<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(task, branchPool));
        }
        List<T> results = new ArrayList<T>(tasks.size());
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception exception) {
                log.warn("假设分支执行失败，跳过该分支: {}", exception.getMessage());
                results.add(null);
            }
        }
        return results;
    }
}
