package com.tjc.bugagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-level configurable properties.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String workspaceRoot;

    /** Agent 异步分析任务在 Redis 中的存活时间（秒），到期自动清理，避免内存/键无限增长。 */
    private long taskTtlSeconds = 1800;

    private final Async async = new Async();
    private final Dbhub dbhub = new Dbhub();
    private final Agent agent = new Agent();
    private final Eval eval = new Eval();
    private final Log log = new Log();

    public Dbhub getDbhub() {
        return dbhub;
    }

    public Agent getAgent() {
        return agent;
    }

    public Eval getEval() {
        return eval;
    }

    public Log getLog() {
        return log;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public long getTaskTtlSeconds() {
        return taskTtlSeconds;
    }

    public void setTaskTtlSeconds(long taskTtlSeconds) {
        this.taskTtlSeconds = taskTtlSeconds;
    }

    public Async getAsync() {
        return async;
    }

    /**
     * Agent 异步分析线程池参数。
     */
    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 8;
        private int queueCapacity = 50;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * 业务库（dbhub 取证）连接池参数，按需调大并发或缩短失败等待。
     */
    public static class Dbhub {
        private int maxPoolSize = 3;
        private int minIdle = 0;
        private long connectionTimeoutMs = 10000;

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }
    }

    /**
     * Agent 分析的轮次与 token 上限，控制单次分析的延迟和成本。
     */
    public static class Agent {
        private int maxIterations = 6;
        private int initialEvidenceLimit = 12000;
        private int sqlTextLimit = 2000;
        private int toolResultLimit = 4000;
        // 收口前是否做一次对抗式自检，抓"看着对"的幻觉结论；关掉可省一次 LLM 调用
        private boolean selfCritique = true;

        public boolean isSelfCritique() {
            return selfCritique;
        }

        public void setSelfCritique(boolean selfCritique) {
            this.selfCritique = selfCritique;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public int getInitialEvidenceLimit() {
            return initialEvidenceLimit;
        }

        public void setInitialEvidenceLimit(int initialEvidenceLimit) {
            this.initialEvidenceLimit = initialEvidenceLimit;
        }

        public int getSqlTextLimit() {
            return sqlTextLimit;
        }

        public void setSqlTextLimit(int sqlTextLimit) {
            this.sqlTextLimit = sqlTextLimit;
        }

        public int getToolResultLimit() {
            return toolResultLimit;
        }

        public void setToolResultLimit(int toolResultLimit) {
            this.toolResultLimit = toolResultLimit;
        }
    }

    /**
     * 评估跑批参数。
     */
    public static class Eval {
        private String casesPath = "./workspace/eval-cases.json";

        public String getCasesPath() {
            return casesPath;
        }

        public void setCasesPath(String casesPath) {
            this.casesPath = casesPath;
        }
    }

    /**
     * 上传日志文件的存储、大小上限与保留天数。
     */
    public static class Log {
        private String dir = "./workspace/logs";
        private long maxBytes = 10 * 1024 * 1024;
        private int retentionDays = 1;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }
}
