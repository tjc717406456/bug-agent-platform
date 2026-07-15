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
    private final Ai ai = new Ai();
    private final Auth auth = new Auth();

    public Auth getAuth() {
        return auth;
    }

    public Ai getAi() {
        return ai;
    }

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
     * AI 调用参数。流式开关出问题可一键关掉退回整段收取。
     */
    /** 鉴权配置：token 有效期、初始管理员、登录失败限流。 */
    public static class Auth {
        /** 登录 token 在 Redis 的存活时间（秒），默认 12 小时；活跃用户会自动滑动续期。 */
        private long tokenTtlSeconds = 12 * 3600;
        /** 初始管理员登录名。 */
        private String adminUsername = "admin";
        /** 初始管理员密码；留空则首次启动随机生成并在日志打印一次。生产建议用环境变量注入。 */
        private String adminInitialPassword;
        /** 同一账号连续登录失败多少次后锁定。 */
        private int maxLoginFailures = 5;
        /** 触发锁定后的冷却时间（秒）。 */
        private long loginLockSeconds = 300;

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminInitialPassword() {
            return adminInitialPassword;
        }

        public void setAdminInitialPassword(String adminInitialPassword) {
            this.adminInitialPassword = adminInitialPassword;
        }

        public int getMaxLoginFailures() {
            return maxLoginFailures;
        }

        public void setMaxLoginFailures(int maxLoginFailures) {
            this.maxLoginFailures = maxLoginFailures;
        }

        public long getLoginLockSeconds() {
            return loginLockSeconds;
        }

        public void setLoginLockSeconds(long loginLockSeconds) {
            this.loginLockSeconds = loginLockSeconds;
        }
    }

    public static class Ai {
        // 开启 SSE 流式收取，逐块到达不再整段干等，治慢网关读超时；与某中转不兼容时配 false 退回非流式
        private boolean streamEnabled = true;
        // 探查/收尾轮强制 tool_choice=required，从源头堵"只给文字计划不调工具"；网关不认会自动降级 auto，表现异常也可一键关
        private boolean toolChoiceRequired = true;
        // 余弦相似度(0~1)折算进召回分的权重：score += cosine * weight，让"意思像"的历史案例排得上号
        // embedding 模型/地址/密钥/超时走 AI 配置里 role=EMBEDDING 的那条，启用即开、停用即退回纯词法，这里只放算法参数
        private int embeddingWeight = 100;
        // 单次分析最多回填多少条缺向量的旧案例，控住首跑延迟；语料会随多次分析逐步补全
        private int embeddingBackfillBudget = 20;

        public boolean isStreamEnabled() {
            return streamEnabled;
        }

        public void setStreamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
        }

        public boolean isToolChoiceRequired() {
            return toolChoiceRequired;
        }

        public void setToolChoiceRequired(boolean toolChoiceRequired) {
            this.toolChoiceRequired = toolChoiceRequired;
        }

        public int getEmbeddingWeight() {
            return embeddingWeight;
        }

        public void setEmbeddingWeight(int embeddingWeight) {
            this.embeddingWeight = embeddingWeight;
        }

        public int getEmbeddingBackfillBudget() {
            return embeddingBackfillBudget;
        }

        public void setEmbeddingBackfillBudget(int embeddingBackfillBudget) {
            this.embeddingBackfillBudget = embeddingBackfillBudget;
        }
    }

    /**
     * 业务库（dbhub 取证）连接池参数，按需调大并发或缩短失败等待。
     */
    public static class Dbhub {
        private int maxPoolSize = 3;
        private int minIdle = 0;
        private long connectionTimeoutMs = 10000;
        // 只读取证查询的行数上限，防止 select * 大表把后端堆撑爆；超出按截断处理并提示模型缩小范围
        private int maxRows = 500;
        // 单条只读 SQL 的执行超时（秒），防止慢查询占住连接拖垮服务
        private int queryTimeoutSeconds = 15;
        // 业务数据详情查询保留的样例条数；跨环境 describe_tables 不读取这些样例
        private int sampleRows = 3;

        public int getSampleRows() {
            return sampleRows;
        }

        public void setSampleRows(int sampleRows) {
            this.sampleRows = sampleRows;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

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
        private int maxIterations = 32;
        // 接口讲解通常 2-4 轮即可闭合，8 轮只作为复杂接口的最终兜底
        private int explainMaxIterations = 8;
        private int initialEvidenceLimit = 12000;
        private int sqlTextLimit = 2000;
        private int toolResultLimit = 4000;
        // get_code_detail 读整段方法，给更大窗口，免得长方法被 toolResultLimit 二次截断丢尾部根因
        private int codeResultLimit = 8000;
        // 上下文衰减：只保留最近 N 轮工具结果全文，更早的折叠成摘要行，削掉深挖案例的 O(n²) token 膨胀。0=不衰减
        private int keepRecentRounds = 6;
        // 收口前是否做一次对抗式自检，抓"看着对"的幻觉结论；关掉可省一次 LLM 调用
        private boolean selfCritique = true;
        // 单个查证工具最长执行时间（秒），超时按失败处理，避免卡死拖垮整轮
        private int toolTimeoutSeconds = 60;
        // 一轮内并行查证工具的线程池大小
        private int toolFanoutPoolSize = 8;
        // 是否把同项目历史确认案例当方向参考喂回分析，总开关，关掉退回从零分析
        private boolean enableSimilarCase = true;
        // 注入的相似案例条数
        private int similarCaseTopK = 3;
        // 相似案例参考文本的总字数上限，防止挤占证据
        private int similarCaseLimit = 1500;
        // 相似度低于此分的案例不注入：100 = 至少完全同接口才召回，挡掉泛词法/相关接口噪声
        private int similarCaseMinScore = 100;
        // 分阶段工具集：开启后未读代码/未看表结构前不放 query_database，逼模型先理解再查库，避免盲查
        private boolean phasedTools = true;
        // 多假设并行模式：OFF 单链；ON 有多候选就并行；AUTO 仅在候选歧义时才并行
        private String hypothesisMode = "AUTO";
        // 并行验证的假设条数上限
        private int hypothesisMaxBranches = 2;
        // AUTO 判歧义：头名与次名置信分差小于此值才算歧义、才 fan out
        private int hypothesisMinScoreGap = 25;
        // 每条假设分支的轮次上限（聚焦单一假设，比单链 maxIterations 小，控成本）
        private int hypothesisChainIterations = 12;
        // 假设分支并行线程池大小，控并发别撞模型限流
        private int hypothesisPoolSize = 2;
        // 多 Agent 子任务只做前置取证，轮次和累计 Token 都要比主链小
        private int multiAgentSubIterations = 4;
        private int multiAgentSubTokenBudget = 30000;
        // 主 Agent 先自行调查若干轮，仍未收口时才按当前证据缺口委派子 Agent
        private int multiAgentDelegateAfterIterations = 2;
        private int multiAgentWaitSeconds = 45;
        // RUNNING 任务超过该秒数没有心跳时，轮询将其修复为 INTERRUPTED
        private int taskStaleSeconds = 600;

        public int getTaskStaleSeconds() {
            return taskStaleSeconds;
        }

        public void setTaskStaleSeconds(int taskStaleSeconds) {
            this.taskStaleSeconds = taskStaleSeconds;
        }

        public boolean isPhasedTools() {
            return phasedTools;
        }

        public void setPhasedTools(boolean phasedTools) {
            this.phasedTools = phasedTools;
        }

        public String getHypothesisMode() {
            return hypothesisMode;
        }

        public void setHypothesisMode(String hypothesisMode) {
            this.hypothesisMode = hypothesisMode;
        }

        public int getHypothesisMaxBranches() {
            return hypothesisMaxBranches;
        }

        public void setHypothesisMaxBranches(int hypothesisMaxBranches) {
            this.hypothesisMaxBranches = hypothesisMaxBranches;
        }

        public int getHypothesisMinScoreGap() {
            return hypothesisMinScoreGap;
        }

        public void setHypothesisMinScoreGap(int hypothesisMinScoreGap) {
            this.hypothesisMinScoreGap = hypothesisMinScoreGap;
        }

        public int getHypothesisChainIterations() {
            return hypothesisChainIterations;
        }

        public void setHypothesisChainIterations(int hypothesisChainIterations) {
            this.hypothesisChainIterations = hypothesisChainIterations;
        }

        public int getHypothesisPoolSize() {
            return hypothesisPoolSize;
        }

        public void setHypothesisPoolSize(int hypothesisPoolSize) {
            this.hypothesisPoolSize = hypothesisPoolSize;
        }

        public int getMultiAgentSubIterations() {
            return multiAgentSubIterations;
        }

        public void setMultiAgentSubIterations(int multiAgentSubIterations) {
            this.multiAgentSubIterations = multiAgentSubIterations;
        }

        public int getMultiAgentSubTokenBudget() {
            return multiAgentSubTokenBudget;
        }

        public void setMultiAgentSubTokenBudget(int multiAgentSubTokenBudget) {
            this.multiAgentSubTokenBudget = multiAgentSubTokenBudget;
        }

        public int getMultiAgentDelegateAfterIterations() {
            return multiAgentDelegateAfterIterations;
        }

        public void setMultiAgentDelegateAfterIterations(int multiAgentDelegateAfterIterations) {
            this.multiAgentDelegateAfterIterations = multiAgentDelegateAfterIterations;
        }

        public int getMultiAgentWaitSeconds() {
            return multiAgentWaitSeconds;
        }

        public void setMultiAgentWaitSeconds(int multiAgentWaitSeconds) {
            this.multiAgentWaitSeconds = multiAgentWaitSeconds;
        }

        public boolean isEnableSimilarCase() {
            return enableSimilarCase;
        }

        public void setEnableSimilarCase(boolean enableSimilarCase) {
            this.enableSimilarCase = enableSimilarCase;
        }

        public int getSimilarCaseTopK() {
            return similarCaseTopK;
        }

        public void setSimilarCaseTopK(int similarCaseTopK) {
            this.similarCaseTopK = similarCaseTopK;
        }

        public int getSimilarCaseLimit() {
            return similarCaseLimit;
        }

        public void setSimilarCaseLimit(int similarCaseLimit) {
            this.similarCaseLimit = similarCaseLimit;
        }

        public int getSimilarCaseMinScore() {
            return similarCaseMinScore;
        }

        public void setSimilarCaseMinScore(int similarCaseMinScore) {
            this.similarCaseMinScore = similarCaseMinScore;
        }

        public int getToolTimeoutSeconds() {
            return toolTimeoutSeconds;
        }

        public void setToolTimeoutSeconds(int toolTimeoutSeconds) {
            this.toolTimeoutSeconds = toolTimeoutSeconds;
        }

        public int getToolFanoutPoolSize() {
            return toolFanoutPoolSize;
        }

        public void setToolFanoutPoolSize(int toolFanoutPoolSize) {
            this.toolFanoutPoolSize = toolFanoutPoolSize;
        }

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

        public int getExplainMaxIterations() {
            return explainMaxIterations;
        }

        public void setExplainMaxIterations(int explainMaxIterations) {
            this.explainMaxIterations = explainMaxIterations;
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

        public int getCodeResultLimit() {
            return codeResultLimit;
        }

        public void setCodeResultLimit(int codeResultLimit) {
            this.codeResultLimit = codeResultLimit;
        }

        public int getKeepRecentRounds() {
            return keepRecentRounds;
        }

        public void setKeepRecentRounds(int keepRecentRounds) {
            this.keepRecentRounds = keepRecentRounds;
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
        private String uploadTempDir = "./workspace/upload-tmp";
        private long maxBytes = 50 * 1024 * 1024;
        private int retentionDays = 1;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getUploadTempDir() {
            return uploadTempDir;
        }

        public void setUploadTempDir(String uploadTempDir) {
            this.uploadTempDir = uploadTempDir;
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
