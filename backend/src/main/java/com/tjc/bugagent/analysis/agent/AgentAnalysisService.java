package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;
import com.tjc.bugagent.analysis.AnalysisRecordRepository;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.AnalysisResult;
import com.tjc.bugagent.analysis.log.LogClues;
import com.tjc.bugagent.analysis.log.LogEvidenceExtractor;
import com.tjc.bugagent.analysis.log.LogStorageService;
import com.tjc.bugagent.analysis.verify.AnalysisAutoVerifier;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.DatasourceSelection;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 只读 Bug 定位 Agent 的编排核心：维护多轮对话、驱动工具查证、判断收敛并产出报告。
 * 提示词、初始证据、工具执行、消息协议、收敛判定、报告生成、持久化等内聚逻辑各自拆给专门协作类，
 * 这里只管串流程。
 */
@Service
public class AgentAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisService.class);
    private static final int MAX_CONTINUOUS_FAILURES = 2;
    // 模型只描述计划不发工具调用时，最多纠偏几次再放弃，专治 gpt-5.5 这类爱"动嘴不动手"的模型
    private static final int MAX_TOOL_NUDGES = 2;

    private final ProjectService projectService;
    private final CodeGraphQueryService codeGraphQueryService;
    private final AgentToolExecutor agentToolExecutor;
    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final LogEvidenceExtractor logEvidenceExtractor;
    private final LogStorageService logStorageService;
    private final AnalysisAutoVerifier analysisAutoVerifier;
    private final AgentPromptBuilder promptBuilder;
    private final PlainAnswerResolver plainAnswerResolver;
    private final AgentToolCallParser toolCallParser;
    private final InitialEvidenceBuilder initialEvidenceBuilder;
    private final AnalysisRecordRepository recordRepository;
    private final AgentConversation conversation;
    private final AgentRoundReporter reporter;
    private final AgentConvergenceJudge judge;
    private final SimilarCaseRetriever similarCaseRetriever;
    private final HypothesisScout hypothesisScout;
    private final HypothesisFanoutExecutor hypothesisFanoutExecutor;
    private final AgentRunner agentRunner;
    private final SubAgentOrchestrator subAgentOrchestrator;

    public AgentAnalysisService(ProjectService projectService,
                                CodeGraphQueryService codeGraphQueryService,
                                AgentToolExecutor agentToolExecutor,
                                AiClient aiClient,
                                AppProperties appProperties,
                                LogEvidenceExtractor logEvidenceExtractor,
                                LogStorageService logStorageService,
                                AnalysisAutoVerifier analysisAutoVerifier,
                                AgentPromptBuilder promptBuilder,
                                PlainAnswerResolver plainAnswerResolver,
                                AgentToolCallParser toolCallParser,
                                InitialEvidenceBuilder initialEvidenceBuilder,
                                AnalysisRecordRepository recordRepository,
                                AgentConversation conversation,
                                AgentRoundReporter reporter,
                                AgentConvergenceJudge judge,
                                SimilarCaseRetriever similarCaseRetriever,
                                HypothesisScout hypothesisScout,
                                HypothesisFanoutExecutor hypothesisFanoutExecutor,
                                AgentRunner agentRunner,
                                SubAgentOrchestrator subAgentOrchestrator) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.agentToolExecutor = agentToolExecutor;
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.logEvidenceExtractor = logEvidenceExtractor;
        this.logStorageService = logStorageService;
        this.analysisAutoVerifier = analysisAutoVerifier;
        this.promptBuilder = promptBuilder;
        this.plainAnswerResolver = plainAnswerResolver;
        this.toolCallParser = toolCallParser;
        this.initialEvidenceBuilder = initialEvidenceBuilder;
        this.recordRepository = recordRepository;
        this.conversation = conversation;
        this.reporter = reporter;
        this.judge = judge;
        this.similarCaseRetriever = similarCaseRetriever;
        this.hypothesisScout = hypothesisScout;
        this.hypothesisFanoutExecutor = hypothesisFanoutExecutor;
        this.agentRunner = agentRunner;
        this.subAgentOrchestrator = subAgentOrchestrator;
    }

    /**
     * 执行 Agent 多轮分析并保存分析记录。
     */
    public AnalysisResult analyze(AnalysisRequest request) {
        return analyze(request, AnalysisProgressListener.NOOP);
    }

    /**
     * 带进度回调的分析：每轮关键动作通过 listener 回传，前端实时展示，告别黑盒干等。
     */
    public AnalysisResult analyze(AnalysisRequest request, AnalysisProgressListener progress) {
        long startMs = System.currentTimeMillis();
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        DatasourceSelection datasourceSelection = projectService.resolveDatasourceSelection(
                request.getProjectId(), request.getEnvironment(), request.getDatabasePolicy());
        applyDatasourceSelection(request, datasourceSelection);
        // 日志原文解析一次，交给线索抽取（一次性用完即弃，不长期持有）
        String logText = resolveLogText(request);
        // 上传的大日志只把路径交给 toolContext，search_log 按需流式 grep，避免全文常驻内存
        String logPath = resolveLogPath(request);
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                progress.executionScope(request.getProjectId(), version.getId(), datasourceSelection),
                request.getApiPath(), request.getLogText(), logPath);

        // 有日志就先抠出堆栈、SQL、traceId、时间，缺啥补啥，让后续流程自动用上
        LogClues logClues = enrichFromLog(request, logText);
        String initialEvidence = initialEvidenceBuilder.buildInitialEvidence(request, version, graph, toolContext, logClues);
        String initialUserPrompt = promptBuilder.buildInitialUserPrompt(initialEvidence);
        List<String> screenshots = parseScreenshotPaths(request.getScreenshotPaths());
        if (aiClient.currentModelSupportsVision() && !screenshots.isEmpty()) {
            progress.onStep("已附带 " + screenshots.size() + " 张报错截图供模型识读");
        }
        // 同项目历史确认案例的方向参考，每条假设链都带上，把人工标注反哺到实时分析
        List<SimilarCase> similar = similarCaseRetriever.retrieve(request, version);
        String refPrompt = promptBuilder.buildSimilarCasesPrompt(similar);
        if (!isBlank(refPrompt)) {
            progress.onStep("已带入 " + similar.size() + " 条同项目历史参考");
            log.info("注入历史参考 projectId={} apiPath={} 命中{}条 {}", request.getProjectId(), request.getApiPath(),
                    similar.size(), similarPaths(similar));
        }
        progress.onStep("已预取入口与调用链源码，开始分析");

        int maxIterations = appProperties.getAgent().getMaxIterations();
        // 按模式跑单链或多假设并行，拿到最终胜出的调查结果
        ChainResult winner = runHypotheses(request, graph, toolContext, initialEvidence,
                initialUserPrompt, screenshots, refPrompt, maxIterations, progress);
        // 并行分支里的中断会被 fanout 吞成 null，这里兜底再查一次，避免停止后还落库/算自检
        if (progress.isCancelled()) {
            throw new AnalysisCancelledException();
        }

        String finalReport = winner.failed ? winner.report
                : reporter.ensureAnalysisReportFormat(winner.report, winner.rounds);
        String evidence = reporter.buildEvidenceLog(initialEvidence, winner.rounds);
        // 汇总产物用预置的最佳单链置信度；合并轮次现算会把"A链读了码+B链查了库"拼成谁也没独立达到的满分
        String confidence = winner.confidence != null ? winner.confidence
                : judge.resolveConfidence(graph, winner.rounds, finalReport);
        // 确定性结论连库自动核对，验证通过的无需人工即可进飞轮
        AnalysisAutoVerifier.Result autoVerify = analysisAutoVerifier.verify(safe(finalReport) + "\n" + evidence,
                datasourceSelection.getSchemaDatasource());
        // 展示与落库用真实循环圈数；rounds 列表是按工具调用逐条记的证据，条数≠轮数
        int roundsCount = winner.iterations;
        long elapsedMs = System.currentTimeMillis() - startMs;
        Long recordId = recordRepository.save(request, version, finalReport, confidence, evidence, autoVerify, roundsCount, winner.tokens);

        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setConfidence(confidence);
        result.setEvidenceJson(evidence);
        result.setAutoVerify(autoVerify.getStatus());
        result.setRounds(roundsCount);
        result.setTotalTokens(winner.tokens);
        result.setElapsedMs(elapsedMs);
        log.info("分析完成 api={} 轮数={} token={} 耗时={}ms 置信={} 自动核对={}",
                request.getApiPath(), roundsCount, winner.tokens, elapsedMs, confidence, autoVerify.getStatus());
        progress.onStep("✓ 分析完成 · " + roundsCount + " 轮查证 · " + confidence + " 置信 · " + winner.tokens + " tokens · " + (elapsedMs / 1000) + "s");
        return result;
    }

    /** 把服务端最终解析出的数据库边界写回请求，供证据展示和历史记录继承。 */
    private void applyDatasourceSelection(AnalysisRequest request, DatasourceSelection selection) {
        request.setEnvironment(selection.getEnvironment());
        request.setDatabaseAccessLevel(selection.getAccessLevel());
        request.setSchemaDatasourceId(selection.getSchemaDatasource() == null ? null : selection.getSchemaDatasource().getId());
        request.setBusinessDatasourceId(selection.getBusinessDatasource() == null ? null : selection.getBusinessDatasource().getId());
    }

    /**
     * 裸模型基线：只把原始报错信息(接口+请求+响应+堆栈+日志)喂给模型，单次作答，
     * 不建代码图谱、不调工具、不查库、不多轮。用于 A/B 对比，量化"代理式流程"相对裸模型的增益。不落库。
     */
    public AnalysisResult analyzeBaseline(AnalysisRequest request) {
        long startMs = System.currentTimeMillis();
        StringBuilder info = new StringBuilder();
        info.append("接口路径: ").append(safe(request.getApiPath())).append("\n");
        appendIf(info, "用户描述", request.getUserDescription());
        appendIf(info, "请求参数", request.getRequestBody());
        appendIf(info, "响应结果", request.getResponseBody());
        appendIf(info, "异常堆栈", request.getStackTrace());
        appendIf(info, "日志", resolveLogText(request));

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system", "你是后端 Bug 定位助手。只能根据下面的报错信息直接判断根因，"
                + "没有源码访问、不能调用工具、不能查数据库。一次性作答。"));
        messages.add(conversation.message("user", "【报错信息】\n" + info
                + "\n直接给出最可能的根因：通俗结论 + 问题结论 + 根因类型，一段话写清，不要罗列排查计划。"));
        AiToolCallResult aiResult = aiClient.chatWithMessages(messages, null);
        String report = safe(aiResult.getContent());

        AnalysisResult result = new AnalysisResult();
        result.setConclusion(report);
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(report, ""));
        result.setConfidence("");
        result.setEvidenceJson("");
        result.setTotalTokens(aiResult.getTotalTokens());
        result.setElapsedMs(System.currentTimeMillis() - startMs);
        return result;
    }

    private void appendIf(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append(label).append(": ").append(value).append("\n");
        }
    }

    /**
     * 按多假设模式决定走单链还是并行：OFF 直接单链；ON/AUTO 先侦察候选根因，
     * AUTO 只在候选歧义（头名次名分差小）时才 fan out，避免简单 bug 白烧 token。
     * 侦察失败、候选不足或分支全挂，一律退回单链兜底，保证不比单链更差。
     */
    private ChainResult runHypotheses(AnalysisRequest request, CodeGraphQueryResult graph,
                                      AgentToolExecutor.AgentToolContext toolContext, String initialEvidence,
                                      String initialUserPrompt, List<String> screenshots, String refPrompt,
                                      int maxIterations, AnalysisProgressListener progress) {
        if (Boolean.TRUE.equals(request.getMultiAgentMode())) {
            AgentRunCheckpoint resumeCheckpoint = progress.resumeCheckpoint();
            if (resumeCheckpoint != null) {
                progress.onStep("检测到主任务检查点，恢复主 Agent 与按需调度状态");
                return runChain(null, maxIterations, graph, toolContext,
                        initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "[主 Agent] ",
                        Collections.<Map<String, Object>>emptyList(),
                        0,
                        AgentRunStateUtils.intValue(resumeCheckpoint.getWorkflowState(), "sideTokens"),
                        AgentRunStateUtils.intValue(resumeCheckpoint.getWorkflowState(), "sideIterations"), true);
            }
            if (Boolean.TRUE.equals(request.getDeepMode())) {
                progress.onStep("多 Agent 调查已开启，本次暂停多假设并行，避免调查链套娃");
            }
            progress.onStep("多 Agent 调度已允许，主 Agent 先自行调查，遇到明确证据缺口时再按需委派");
            return runChain(null, maxIterations, graph, toolContext,
                    initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "[主 Agent] ",
                    Collections.<Map<String, Object>>emptyList(),
                    0, 0, 0, true);
        }
        String mode = resolveHypothesisMode(request);
        log.info("多假设模式 · 全局配置={} · 请求deepMode={} · 实际生效={}",
                appProperties.getAgent().getHypothesisMode(), request.getDeepMode(), mode);
        if ("OFF".equals(mode)) {
            return runChain(null, maxIterations, graph, toolContext, initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "");
        }
        // 侦察等旁路 LLM 调用的 token 也要入账，别让落库的 totalTokens 低报
        AtomicInteger sideTokens = new AtomicInteger();
        List<Hypothesis> hypotheses = hypothesisScout.scout(initialEvidence, sideTokens);
        boolean fanout = hypotheses.size() >= 2 && ("ON".equals(mode) || isAmbiguous(hypotheses));
        List<Hypothesis> chosen = fanout ? pickBranches(hypotheses) : new ArrayList<Hypothesis>();
        if (chosen.size() < 2) {
            if (!hypotheses.isEmpty()) {
                progress.onStep("侦察完成 · 根因方向明确，单链深挖");
            }
            return withExtraTokens(runChain(null, maxIterations, graph, toolContext, initialEvidence,
                    initialUserPrompt, screenshots, refPrompt, progress, ""), sideTokens.get());
        }

        int chainIterations = appProperties.getAgent().getHypothesisChainIterations();
        progress.onStep("侦察完成 · 发现 " + chosen.size() + " 个相近根因，并行验证");
        List<Supplier<ChainResult>> tasks = new ArrayList<Supplier<ChainResult>>();
        for (int i = 0; i < chosen.size(); i++) {
            final Hypothesis hypothesis = chosen.get(i);
            final String label = "[假设" + (i + 1) + "] ";
            tasks.add(() -> runChain(hypothesis.getCause(), chainIterations, graph, toolContext,
                    initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, label));
        }
        List<ChainResult> branchResults = hypothesisFanoutExecutor.runAll(tasks);
        List<ChainResult> ok = new ArrayList<ChainResult>();
        for (ChainResult result : branchResults) {
            // AI 挂掉的分支报告是错误横幅不是结论，绝不能混进汇总
            if (result != null && !result.failed && !isBlank(result.report)) {
                ok.add(result);
            }
        }
        if (ok.isEmpty()) {
            progress.onStep("假设分支均未产出，退回单链兜底");
            return withExtraTokens(runChain(null, maxIterations, graph, toolContext, initialEvidence,
                    initialUserPrompt, screenshots, refPrompt, progress, ""), sideTokens.get());
        }
        if (ok.size() == 1) {
            return withExtraTokens(ok.get(0), sideTokens.get());
        }
        return withExtraTokens(synthesize(ok, graph, progress), sideTokens.get());
    }

    /** 入选分支门槛：与头名分差超过两倍歧义阈值的凑数假设，不值得白拿一条链的预算；最多取 maxBranches 个。 */
    private List<Hypothesis> pickBranches(List<Hypothesis> hypotheses) {
        int maxBranches = appProperties.getAgent().getHypothesisMaxBranches();
        int window = appProperties.getAgent().getHypothesisMinScoreGap() * 2;
        int topScore = hypotheses.get(0).getScore();
        List<Hypothesis> chosen = new ArrayList<Hypothesis>();
        for (Hypothesis hypothesis : hypotheses) {
            if (chosen.size() >= maxBranches || topScore - hypothesis.getScore() >= window) {
                break;
            }
            chosen.add(hypothesis);
        }
        return chosen;
    }

    /** 把侦察等旁路 LLM 开销并入最终结果的 token 账。 */
    private ChainResult withExtraTokens(ChainResult result, int extra) {
        if (extra <= 0) {
            return result;
        }
        return new ChainResult(result.report, result.rounds, result.tokens + extra, result.failed, result.confidence, result.iterations);
    }

    /** per-request 的 deepMode 优先：true 开启多假设侦察（是否真并行仍看候选分差，方向明确不硬劈）、false 强制单链；否则用全局配置。 */
    private String resolveHypothesisMode(AnalysisRequest request) {
        if (Boolean.TRUE.equals(request.getDeepMode())) {
            return "AUTO";
        }
        if (Boolean.FALSE.equals(request.getDeepMode())) {
            return "OFF";
        }
        // YAML 可能把 OFF/NO 解析成布尔→字符串"FALSE"，TRUE/YES→"TRUE"，这里统一归一，别让 OFF 判断漏网
        String mode = safe(appProperties.getAgent().getHypothesisMode()).trim().toUpperCase();
        if (mode.isEmpty() || "FALSE".equals(mode) || "NO".equals(mode) || "0".equals(mode)) {
            return "OFF";
        }
        if ("TRUE".equals(mode) || "YES".equals(mode) || "1".equals(mode)) {
            return "ON";
        }
        return mode;
    }

    /** 头名与次名置信分差小于阈值即判歧义，需要并行验证。候选已按分降序。 */
    private boolean isAmbiguous(List<Hypothesis> hypotheses) {
        if (hypotheses.size() < 2) {
            return false;
        }
        int gap = hypotheses.get(0).getScore() - hypotheses.get(1).getScore();
        return gap < appProperties.getAgent().getHypothesisMinScoreGap();
    }

    /**
     * 汇总多条假设链：再发一次 LLM 让它基于各自证据挑出最硬的根因产出终报告；
     * 汇总失败就退回结构化置信度最高的分支（轮次多不等于证据硬），证据日志合并所有分支供追溯。
     */
    private ChainResult synthesize(List<ChainResult> branches, CodeGraphQueryResult graph, AnalysisProgressListener progress) {
        log.info("多假设分支查证完成，开始流式汇总最终报告，分支数={}", branches.size());
        progress.onStep("汇总各假设结论，挑出证据最硬的根因");
        StringBuilder reports = new StringBuilder();
        List<Map<String, Object>> mergedRounds = new ArrayList<Map<String, Object>>();
        int mergedTokens = 0;
        // 并行分支的"轮数"按总投入累加：两条链各查 8 轮就是 16 轮工作量
        int mergedIterations = 0;
        for (int i = 0; i < branches.size(); i++) {
            reports.append("【假设").append(i + 1).append("的调查结论】\n").append(safe(branches.get(i).report)).append("\n\n");
            mergedRounds.addAll(branches.get(i).rounds);
            mergedTokens += branches.get(i).tokens;
            mergedIterations += branches.get(i).iterations;
        }
        String synthesized;
        try {
            // 汇总终稿是单人写单稿，不存在分支快照互相覆盖，可以放心流式投给前端
            List<Map<String, Object>> ask = new ArrayList<Map<String, Object>>();
            ask.add(conversation.message("user", promptBuilder.buildHypothesisSynthesisPrompt(reports.toString())));
            final long[] lastPushMs = {0};
            AiToolCallResult synthesis = aiClient.chatProseStream(ask, snapshot -> {
                long now = System.currentTimeMillis();
                if (now - lastPushMs[0] >= 400) {
                    lastPushMs[0] = now;
                    progress.onPartialReport(snapshot);
                }
            });
            mergedTokens += synthesis.getTotalTokens();
            synthesized = synthesis.isFailed() ? null : synthesis.getContent();
            if (!isBlank(synthesized)) {
                progress.onPartialReport(synthesized);
            }
        } catch (Exception exception) {
            synthesized = null;
        }
        // 各分支独立算置信度：兜底时选最佳分支；汇总成功时把最佳单链的置信度带给终稿
        ChainResult best = branches.get(0);
        String bestConfidence = judge.resolveConfidence(graph, best.rounds, best.report);
        int bestRank = confidenceRank(bestConfidence);
        for (int i = 1; i < branches.size(); i++) {
            ChainResult branch = branches.get(i);
            String branchConfidence = judge.resolveConfidence(graph, branch.rounds, branch.report);
            int rank = confidenceRank(branchConfidence);
            // 同置信度再比轮次当平手加分
            if (rank > bestRank || (rank == bestRank && branch.rounds.size() > best.rounds.size())) {
                best = branch;
                bestRank = rank;
                bestConfidence = branchConfidence;
            }
        }
        if (isBlank(synthesized) || synthesized.startsWith("AI")) {
            return best;
        }
        return new ChainResult(synthesized, mergedRounds, mergedTokens, false, bestConfidence, mergedIterations);
    }

    private int confidenceRank(String confidence) {
        if ("HIGH".equals(confidence)) {
            return 3;
        }
        if ("MEDIUM".equals(confidence)) {
            return 2;
        }
        return 1;
    }

    /**
     * 一条完整调查链：自建对话、跑多轮工具循环、收口产出报告，返回结果不落库（落库交给编排层统一做）。
     * hypothesisHint 非空时让本链聚焦核验该假设。整套收敛、纠偏、自检、兜底逻辑与单链一致。
     */
    private ChainResult runChain(String hypothesisHint, int maxIterations, CodeGraphQueryResult graph,
                                 AgentToolExecutor.AgentToolContext toolContext, String initialEvidence,
                                 String initialUserPrompt, List<String> screenshots, String refPrompt,
                                 AnalysisProgressListener progress, String label) {
        return runChain(hypothesisHint, maxIterations, graph, toolContext, initialEvidence, initialUserPrompt,
                screenshots, refPrompt, progress, label, Collections.<Map<String, Object>>emptyList(), 0, 0, 0, false);
    }

    /**
     * 支持从子 Agent 的结构化轮次继续调查；普通单链传空集合和 0，行为保持不变。
     */
    private ChainResult runChain(String hypothesisHint, int maxIterations, CodeGraphQueryResult graph,
                                 AgentToolExecutor.AgentToolContext toolContext, String initialEvidence,
                                 String initialUserPrompt, List<String> screenshots, String refPrompt,
                                 AnalysisProgressListener progress, String label,
                                 List<Map<String, Object>> seedRounds, int maxTotalTokens,
                                 int sideTokens, int sideIterations, boolean allowSubAgentDelegation) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system", promptBuilder.buildSystemPrompt()));
        if (aiClient.currentModelSupportsVision() && !screenshots.isEmpty()) {
            messages.add(conversation.userMessageWithImages(initialUserPrompt, screenshots));
        } else {
            messages.add(conversation.message("user", initialUserPrompt));
        }
        if (!isBlank(refPrompt)) {
            messages.add(conversation.message("user", refPrompt));
        }
        if (!isBlank(hypothesisHint)) {
            messages.add(conversation.message("user", promptBuilder.buildHypothesisHintPrompt(hypothesisHint)));
        }

        List<Map<String, Object>> rounds = seedRounds == null
                ? new ArrayList<Map<String, Object>>() : new ArrayList<Map<String, Object>>(seedRounds);
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(messages);
        spec.setToolContext(toolContext);
        spec.setMaxIterations(maxIterations);
        spec.setMaxTotalTokens(maxTotalTokens);
        spec.setKeepRecentRounds(appProperties.getAgent().getKeepRecentRounds());
        spec.setVerificationEnabled(!appProperties.getAgent().isPhasedTools() || unlocksVerification(seedRounds));
        spec.setModelRole(AgentRunSpec.ModelRole.PRIMARY);
        spec.setProgress(progress);
        spec.setModelFailureMessage("⚠️ AI 服务暂不可用（网关连接异常/重置），本次分析中断，请稍后重试。");
        BugAnalysisRunPolicy policy = new BugAnalysisRunPolicy(graph, toolContext, initialEvidence, rounds, progress,
                label, maxIterations, sideTokens, sideIterations, allowSubAgentDelegation);
        spec.setPolicy(policy);
        AgentRunResult runResult = agentRunner.run(spec);
        return new ChainResult(runResult.getFinalContent(), rounds,
                runResult.getTotalTokens() + policy.getSideTokens(), runResult.isFailed(), null,
                runResult.getIterations() + policy.getSideIterations());
    }

    /** Bug 定位的领域策略：收敛、自检和证据判定留在业务层，机械迭代交给 AgentRunner。 */
    private final class BugAnalysisRunPolicy implements AgentRunPolicy {
        private final CodeGraphQueryResult graph;
        private final AgentToolExecutor.AgentToolContext toolContext;
        private final String initialEvidence;
        private final List<Map<String, Object>> rounds;
        private final AnalysisProgressListener progress;
        private final String label;
        private final int maxIterations;
        private int continuousFailures;
        private int noNewKeyFactRounds;
        private int toolNudges;
        private boolean selfChecked;
        private boolean budgetWarned;
        private boolean coreEvidenceHinted;
        private String forceFinishReason;
        private int sideTokens;
        private int sideIterations;
        private int mainToolCalls;
        private final boolean allowSubAgentDelegation;
        private boolean subAgentsDelegated;

        private BugAnalysisRunPolicy(CodeGraphQueryResult graph, AgentToolExecutor.AgentToolContext toolContext,
                                     String initialEvidence, List<Map<String, Object>> rounds,
                                     AnalysisProgressListener progress, String label, int maxIterations,
                                     int sideTokens, int sideIterations, boolean allowSubAgentDelegation) {
            this.graph = graph;
            this.toolContext = toolContext;
            this.initialEvidence = initialEvidence;
            this.rounds = rounds;
            this.progress = progress;
            this.label = label;
            this.maxIterations = maxIterations;
            this.sideTokens = sideTokens;
            this.sideIterations = sideIterations;
            this.allowSubAgentDelegation = allowSubAgentDelegation;
        }

        @Override
        public List<AgentToolCall> filterToolCalls(AgentRunContext context, List<AgentToolCall> calls) {
            if (!isMultiAgentMain() || !subAgentsDelegated) {
                return calls;
            }
            int remaining = Math.max(0, appProperties.getAgent().getMultiAgentMainToolBudget() - mainToolCalls);
            int allowed = Math.min(2, remaining);
            List<AgentToolCall> filtered = new ArrayList<AgentToolCall>();
            for (AgentToolCall call : calls) {
                if ("finish".equals(call.getAction())) {
                    filtered.add(call);
                } else if (allowed > 0) {
                    filtered.add(call);
                    allowed--;
                }
            }
            return filtered;
        }

        public void beforeIteration(AgentRunContext context) {
            forceFinishReason = judge.resolveForceFinishReason(noNewKeyFactRounds);
            if (!isBlank(forceFinishReason)) {
                log.info("{}第{}轮 · 触发强制收口 · 原因={}", label, context.getIteration(), forceFinishReason);
                context.getMessages().add(conversation.message("user",
                        promptBuilder.buildForceFinishInstruction(forceFinishReason)));
            } else if (!coreEvidenceHinted && judge.coreEvidenceGathered(graph, rounds)) {
                coreEvidenceHinted = true;
                context.getMessages().add(conversation.message("user", "【收口提示】入口、源码、数据库三类核心证据已齐。"
                        + "若已能完整解释问题请直接调用 finish；仍有关键断点则只补最必要的证据。"));
            } else if (!budgetWarned && context.getIteration() >= Math.max(1, maxIterations * 2 / 3)) {
                budgetWarned = true;
                context.getMessages().add(conversation.message("user",
                        promptBuilder.buildBudgetReminder(context.getIteration(), maxIterations)));
            }
        }

        public void afterModelResponse(AgentRunContext context, AiToolCallResult response) {
            if (!isBlank(response.getContent())) {
                log.info("{}第{}轮 · 思考: {}", label, context.getIteration(), trim(safe(response.getContent()), 200));
            }
        }

        public AgentToolCall resolveFinish(AgentRunContext context, List<AgentToolCall> calls, AgentToolCall finish) {
            if (!isBlank(forceFinishReason) && finish == null) {
                return toolCallParser.finishCall(reporter.buildForcedFinishReport(forceFinishReason, rounds));
            }
            return finish;
        }

        public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult response, AgentToolCall finish) {
            int iteration = context.getIteration();
            if (finish.isDegraded() && isBlank(forceFinishReason) && toolNudges < MAX_TOOL_NUDGES) {
                toolNudges++;
                conversation.appendAssistantMessage(context.getMessages(), response);
                context.getMessages().add(conversation.message("user", promptBuilder.buildToolNudge()));
                progress.onStep(label + "第" + iteration + "轮 · 模型只给了计划，提示其直接调用工具");
                return AgentRunDirective.continueRun();
            }
            if (finish.isDegraded()) {
                log.info("{}模型未按协议收口，依据已查证据开始生成最终报告", label);
                AtomicInteger tokenSink = new AtomicInteger();
                String report = proseSynthesizeReport(context.getMessages(), tokenSink);
                context.addTokens(tokenSink.get());
                if (isBlank(report)) {
                    String reason = isBlank(forceFinishReason) ? "模型未按协议收口，依据已查证据汇总" : forceFinishReason;
                    report = reporter.buildForcedFinishReport(reason, rounds);
                }
                rounds.add(reporter.recordRound(iteration, response, finish,
                        AgentToolResult.ok("finish", "已据证据生成最终报告", report)));
                return AgentRunDirective.stop(report, AgentStopReason.FINISH_TOOL);
            }

            boolean canCritique = appProperties.getAgent().isSelfCritique()
                    && !selfChecked && isBlank(forceFinishReason) && finish.getToolCallId() != null;
            String report = finish.stringArg("report");
            boolean twoPhase = safe(report).trim().length() < 300
                    && isBlank(forceFinishReason) && finish.getToolCallId() != null;
            String reframeReason = null;
            if (canCritique) {
                // 无论复核通过还是打回补证，一条调查链只做一次独立自检，防止反复抬高证明标准
                selfChecked = true;
                AtomicInteger tokenSink = new AtomicInteger();
                String critiqueTarget = isBlank(report) ? safe(response.getContent()) : report;
                String verdict = judge.runSelfCritique(critiqueTarget, rounds, initialEvidence, tokenSink);
                context.addTokens(tokenSink.get());
                if (judge.isReviseVerdict(verdict)) {
                    String reviseReason = judge.reviseReason(verdict);
                    log.info("{}第{}轮 · 结论自检未通过：{}；继续补证",
                            label, iteration, trim(reviseReason, 160));
                    conversation.appendAssistantMessage(context.getMessages(), response);
                    conversation.appendFinishHold(context.getMessages(), response, finish);
                    context.getMessages().add(conversation.message("user", "对你的初步结论做了独立复核，未通过："
                            + reviseReason + " 请针对这一点补查证据，确认后再调用 finish。"));
                    rounds.add(reporter.recordCritique(iteration, critiqueTarget, verdict));
                    progress.onStep(label + "第" + iteration + "轮 · 结论自检未通过："
                            + trim(reviseReason, 160) + "；继续补证");
                    return AgentRunDirective.continueRun();
                }
                if (judge.isReframeVerdict(verdict)) {
                    reframeReason = judge.reframeReason(verdict);
                    rounds.add(reporter.recordCritique(iteration, critiqueTarget, verdict,
                            "结论自检通过，最终报告需收窄措辞"));
                    log.info("{}结论概要自检通过，按复核意见收窄措辞：{}", label, trim(reframeReason, 160));
                    progress.onStep(label + "结论概要自检通过，正在收窄措辞并生成最终报告");
                } else {
                    log.info("{}结论概要自检通过", label);
                    progress.onStep(label + "结论概要自检通过，开始生成最终报告");
                }
            }
            boolean needsProseReport = twoPhase || !isBlank(reframeReason);
            if (needsProseReport) {
                AtomicInteger tokenSink = new AtomicInteger();
                report = streamFinalReport(context.getMessages(), response, finish, progress, tokenSink, label, reframeReason);
                context.addTokens(tokenSink.get());
                if (isBlank(report)) {
                    return AgentRunDirective.stop(null, AgentStopReason.TOOL_ERROR);
                }
            }
            report = reporter.ensureAnalysisReportFormat(report, rounds);
            if (needsProseReport) {
                rounds.add(reporter.recordRound(iteration, response, finish,
                        AgentToolResult.ok("finish", "收口信号，报告已流式生成", report)));
                return AgentRunDirective.stop(report, AgentStopReason.FINISH_TOOL);
            }
            log.info("{}证据足够，接收模型完整报告", label);
            progress.onStep(label + "证据足够，正在生成最终报告");
            AgentToolResult finishResult = agentToolExecutor.execute(finish, toolContext);
            rounds.add(reporter.recordRound(iteration, response, finish, finishResult));
            return AgentRunDirective.stop(finishResult.getEvidence(), AgentStopReason.FINISH_TOOL);
        }

        public AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult response,
                                            List<AgentToolCall> calls, List<AgentToolResult> results) {
            boolean anyOk = false;
            boolean anyNewFact = false;
            boolean anyHardFailure = false;
            for (int index = 0; index < calls.size(); index++) {
                AgentToolCall call = calls.get(index);
                AgentToolResult result = results.get(index);
                boolean newFact = judge.isNewKeyFact(rounds, call, result);
                rounds.add(reporter.recordRound(context.getIteration(), response, call, result));
                progress.onStep(label + "第" + context.getIteration() + "轮 · " + reporter.actionName(call.getAction())
                        + " · " + trim(safe(result.getSummary()), 40));
                anyOk = anyOk || result.isOk();
                anyNewFact = anyNewFact || newFact;
                anyHardFailure = anyHardFailure || result.isHardFailure();
                if (!context.isVerificationEnabled() && result.isOk() && unlocksVerify(call.getAction())) {
                    context.setVerificationEnabled(true);
                    progress.onStep(label + "已理解代码/表结构，进入查库取证阶段");
                }
            }
            if (anyOk) {
                continuousFailures = 0;
                noNewKeyFactRounds = anyNewFact ? 0 : noNewKeyFactRounds + 1;
            } else if (anyHardFailure) {
                continuousFailures++;
            } else {
                noNewKeyFactRounds++;
            }
            log.info("{}第{}轮 · 动作=[{}] · 结果={} · 新事实={} · 空转={}/{} · 失败={}/{}", label,
                    context.getIteration(), actionNames(calls), anyOk ? "OK" : (anyHardFailure ? "FAIL" : "空"),
                    anyNewFact, noNewKeyFactRounds, AgentConvergenceJudge.MAX_NO_NEW_FACT_ROUNDS,
                    continuousFailures, MAX_CONTINUOUS_FAILURES);
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                return AgentRunDirective.stop(reporter.buildFailureReport(rounds), AgentStopReason.CONTINUOUS_TOOL_FAILURES);
            }
            boolean delegatedThisRound = false;
            if (shouldDelegate(context)) {
                boolean codeNeeded = needsCodeAgent(calls);
                boolean logNeeded = needsLogAgent(calls);
                if (codeNeeded || logNeeded) {
                    subAgentsDelegated = true;
                    delegatedThisRound = true;
                    progress.onStep(label + "当前调查出现明确缺口，按需委派"
                            + (codeNeeded ? "源码 Agent" : "")
                            + (codeNeeded && logNeeded ? "和" : "")
                            + (logNeeded ? "日志 Agent" : ""));
                    SubAgentInvestigation investigation = subAgentOrchestrator.investigate(
                            focusedDelegationEvidence(response, calls), toolContext, progress, codeNeeded, logNeeded);
                    toolContext.restoreCachedResults(investigation.cachedToolResults());
                    rounds.addAll(investigation.rounds());
                    sideTokens += investigation.totalTokens();
                    sideIterations += investigation.totalIterations();
                    if (!investigation.getResults().isEmpty()) {
                        context.getMessages().add(conversation.message("user", investigation.evidencePrompt()));
                        progress.onStep(label + "子 Agent 已交接定向证据，主 Agent 继续裁决");
                    }
                }
            }
            if (isMultiAgentMain() && subAgentsDelegated && !delegatedThisRound) {
                mainToolCalls += calls.size();
                if (mainToolCalls >= appProperties.getAgent().getMultiAgentMainToolBudget()) {
                    progress.onStep(label + "已完成补缺口工具预算，停止继续探索并生成报告");
                    AtomicInteger tokenSink = new AtomicInteger();
                    String report = forceSynthesizeReport(context.getMessages(), toolContext, rounds,
                            context.getIteration() + 1, tokenSink, progress, label, AgentStopReason.TOOL_BUDGET);
                    context.addTokens(tokenSink.get());
                    return AgentRunDirective.stop(report, AgentStopReason.TOOL_BUDGET);
                }
            }
            return AgentRunDirective.continueRun();
        }

        public String finalizeRun(AgentRunContext context) {
            AtomicInteger tokenSink = new AtomicInteger();
            String report = forceSynthesizeReport(context.getMessages(), toolContext, rounds,
                    context.getIteration() + 1, tokenSink, progress, label, context.getStopReason());
            context.addTokens(tokenSink.get());
            return report;
        }

        public Map<String, Object> snapshotState(AgentRunContext context) {
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("rounds", new ArrayList<Map<String, Object>>(rounds));
            state.put("continuousFailures", continuousFailures);
            state.put("noNewKeyFactRounds", noNewKeyFactRounds);
            state.put("toolNudges", toolNudges);
            state.put("selfChecked", selfChecked);
            state.put("budgetWarned", budgetWarned);
            state.put("coreEvidenceHinted", coreEvidenceHinted);
            state.put("forceFinishReason", forceFinishReason);
            state.put("sideTokens", sideTokens);
            state.put("sideIterations", sideIterations);
            state.put("mainToolCalls", mainToolCalls);
            state.put("subAgentsDelegated", subAgentsDelegated);
            return state;
        }

        public void restoreState(AgentRunContext context, Map<String, Object> state) {
            rounds.clear();
            rounds.addAll(AgentRunStateUtils.rounds(state));
            continuousFailures = AgentRunStateUtils.intValue(state, "continuousFailures");
            noNewKeyFactRounds = AgentRunStateUtils.intValue(state, "noNewKeyFactRounds");
            toolNudges = AgentRunStateUtils.intValue(state, "toolNudges");
            selfChecked = AgentRunStateUtils.boolValue(state, "selfChecked");
            budgetWarned = AgentRunStateUtils.boolValue(state, "budgetWarned");
            coreEvidenceHinted = AgentRunStateUtils.boolValue(state, "coreEvidenceHinted");
            Object reason = state == null ? null : state.get("forceFinishReason");
            forceFinishReason = reason == null ? null : String.valueOf(reason);
            sideTokens = AgentRunStateUtils.intValue(state, "sideTokens");
            sideIterations = AgentRunStateUtils.intValue(state, "sideIterations");
            mainToolCalls = AgentRunStateUtils.intValue(state, "mainToolCalls");
            subAgentsDelegated = AgentRunStateUtils.boolValue(state, "subAgentsDelegated");
        }

        private int getSideTokens() { return sideTokens; }
        private int getSideIterations() { return sideIterations; }
        private boolean isMultiAgentMain() { return safe(label).startsWith("[主 Agent]"); }

        private boolean shouldDelegate(AgentRunContext context) {
            if (!allowSubAgentDelegation || subAgentsDelegated) {
                return false;
            }
            int threshold = Math.max(1, appProperties.getAgent().getMultiAgentDelegateAfterIterations());
            return (context.getIteration() >= threshold && noNewKeyFactRounds > 0)
                    || context.getIteration() >= threshold + 2;
        }

        private boolean needsCodeAgent(List<AgentToolCall> calls) {
            for (AgentToolCall call : calls) {
                String action = safe(call.getAction());
                if ("search_code".equals(action) || "get_code_detail".equals(action)
                        || "trace_call_chain".equals(action) || "search_sql".equals(action)
                        || "grep_source".equals(action) || "find_callers".equals(action)) {
                    return true;
                }
            }
            return false;
        }

        private boolean needsLogAgent(List<AgentToolCall> calls) {
            for (AgentToolCall call : calls) {
                if ("search_log".equals(call.getAction())) {
                    return true;
                }
            }
            return false;
        }

        private String focusedDelegationEvidence(AiToolCallResult response, List<AgentToolCall> calls) {
            StringBuilder evidence = new StringBuilder();
            evidence.append("【主 Agent 当前未闭合断点】\n")
                    .append(trim(safe(response.getContent()), 1200)).append("\n")
                    .append("【本轮尝试工具】\n").append(actionNames(calls)).append("\n")
                    .append("【最近结构化证据】\n");
            int from = Math.max(0, rounds.size() - 6);
            for (int index = from; index < rounds.size(); index++) {
                Map<String, Object> round = rounds.get(index);
                evidence.append("- ").append(safe(round.get("action"))).append(' ')
                        .append(trim(safe(round.get("arguments")), 180)).append("：")
                        .append(trim(safe(round.get("toolSummary")), 240)).append("\n")
                        .append(trim(safe(round.get("toolEvidence")), 600)).append("\n");
            }
            evidence.append("只调查上述未闭合断点，不要从接口入口重新开始。");
            return AgentTextUtils.trim(evidence.toString(), 6000);
        }
    }

    /** 一条调查链的产出：最终报告 + 全部轮次记录，不含落库。 */
    private static final class ChainResult {
        private final String report;
        private final List<Map<String, Object>> rounds;
        private final int tokens;
        // AI 真失败的链：报告是错误横幅，汇总时必须排除
        private final boolean failed;
        // 汇总产物预置的置信度(取最佳单链)；null 表示由编排层按本链轮次现算
        private final String confidence;
        // 真实循环圈数。rounds 列表按工具调用逐条记(一圈可并行多调用)，对外展示"轮"必须用这个，别拿 rounds.size() 冒充
        private final int iterations;

        private ChainResult(String report, List<Map<String, Object>> rounds, int tokens, boolean failed, String confidence, int iterations) {
            this.report = report;
            this.rounds = rounds;
            this.tokens = tokens;
            this.failed = failed;
            this.confidence = confidence;
            this.iterations = iterations;
        }
    }

    // 收口后纯文字报告的统一指令：两段式流式与 degraded 救场共用一套要求
    private static final String PROSE_REPORT_INSTRUCTION = "现在不要调用任何工具，直接用纯文字写出最终定位报告，"
            + "必须严格按以下七段标题输出，不得省略标题，不得缩写成单段摘要：\n"
            + "【通俗结论】\n【问题结论】\n【证据链路】\n【关键代码/SQL/数据证据】\n"
            + "【根因类型】\n【建议处理人】\n【置信度】\n"
            + "证据不足时在对应章节写明最可能判断和剩余风险，不得编造证据。";

    /**
     * 两段式收口第二段：闭合 finish 信号后，用纯文字流式生成完整报告；REFRAME 同样走这里收窄措辞。
     * 纯 content 流式不踩网关丢 tool_calls 的坑，分块回传也不怕读超时；累计快照经 progress 节流推给前端渐进展示。
     * 并行假设分支不往前端推（多链快照互相覆盖只会看花眼），只在单链时推。失败返回 null 由调用方兜底。
     */
    private String streamFinalReport(List<Map<String, Object>> messages, AiToolCallResult aiResult, AgentToolCall finish,
                                     AnalysisProgressListener progress, AtomicInteger tokens, String label, String reframeReason) {
        conversation.appendAssistantMessage(messages, aiResult);
        conversation.appendFinishAck(messages, aiResult, finish);
        String reportInstruction = isBlank(reframeReason) ? PROSE_REPORT_INSTRUCTION
                : "独立复核认为核心结论成立，但最终报告需要收窄措辞：" + reframeReason
                + "。只修正结论边界并明确剩余风险，不要继续补查，也不要把未确认细节写成事实。\n"
                + PROSE_REPORT_INSTRUCTION;
        messages.add(conversation.message("user", reportInstruction));
        log.info("{}证据足够，开始流式生成最终报告", label);
        progress.onStep(label + "证据足够，开始生成最终报告");
        boolean pushPartial = !isHypothesisBranch(label);
        final long[] lastPushMs = {0};
        AiToolCallResult prose = aiClient.chatProseStream(messages, snapshot -> {
            if (!pushPartial) {
                return;
            }
            long now = System.currentTimeMillis();
            // 400ms 节流：前端流式阶段 500ms 轮询一拍，与之配套
            if (now - lastPushMs[0] >= 400) {
                lastPushMs[0] = now;
                progress.onPartialReport(snapshot);
            }
        });
        tokens.addAndGet(prose.getTotalTokens());
        if (prose.isFailed() || isBlank(prose.getContent())) {
            return null;
        }
        if (pushPartial) {
            progress.onPartialReport(prose.getContent());
        }
        return prose.getContent();
    }

    /**
     * 模型憋不出 finish 工具调用时的救场：让它「不调任何工具、纯文字」直接写最终报告。
     * 散文输出没有 tool-call JSON 解析这关，绕开上下文过大导致的协议崩，多数情况下能拿到真分析。
     * 失败或返回空则回 null，由调用方退回机械汇总。
     */
    private String proseSynthesizeReport(List<Map<String, Object>> messages, AtomicInteger tokenSink) {
        try {
            List<Map<String, Object>> ask = new ArrayList<Map<String, Object>>(messages);
            ask.add(conversation.message("user", PROSE_REPORT_INSTRUCTION));
            AiToolCallResult aiResult = aiClient.chatWithMessages(ask, null);
            tokenSink.addAndGet(aiResult.getTotalTokens());
            if (aiResult.isFailed()) {
                return null;
            }
            return safe(aiResult.getContent());
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 达到最大轮次仍没收口时的收尾：不再要求模型把全文塞进 finish 参数（模型常只给一句概要糊弄），
     * 直接纯文字流式生成完整报告，单链时前端同样渐进可见；失败退回本地汇总。
     */
    private String forceSynthesizeReport(List<Map<String, Object>> messages, AgentToolExecutor.AgentToolContext toolContext,
                                         List<Map<String, Object>> rounds, int iteration, AtomicInteger tokenSink,
                                         AnalysisProgressListener progress, String label, AgentStopReason stopReason) {
        try {
            String finishReason = stopReason == AgentStopReason.TOKEN_BUDGET ? "已达到本次 Token 预算"
                    : stopReason == AgentStopReason.TOOL_BUDGET ? "已完成本次补缺口工具预算" : "已达到最大分析轮次";
            log.info("{}{}，查证结束，开始流式生成最终报告", label, finishReason);
            progress.onStep(label + finishReason + "，开始生成最终报告");
            messages.add(conversation.message("user", finishReason + "。请基于全部已确认事实和复核意见完成收尾。"
                    + PROSE_REPORT_INSTRUCTION));
            boolean pushPartial = !isHypothesisBranch(label);
            final long[] lastPushMs = {0};
            AiToolCallResult prose = aiClient.chatProseStream(messages, snapshot -> {
                if (!pushPartial) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastPushMs[0] >= 400) {
                    lastPushMs[0] = now;
                    progress.onPartialReport(snapshot);
                }
            });
            tokenSink.addAndGet(prose.getTotalTokens());
            if (!prose.isFailed() && !isBlank(prose.getContent())) {
                String report = reporter.ensureAnalysisReportFormat(prose.getContent(), rounds);
                if (pushPartial) {
                    progress.onPartialReport(report);
                }
                rounds.add(reporter.recordRound(iteration, prose, toolCallParser.finishCall(report),
                        AgentToolResult.ok("finish", "已达最大轮次，报告以统一格式生成", report)));
                return report;
            }
        } catch (Exception exception) {
            // 兜底报告照样给，不让异常打断收尾
        }
        return reporter.buildMaxRoundReport(rounds);
    }

    private boolean isHypothesisBranch(String label) {
        return safe(label).startsWith("[假设");
    }

    private ProjectVersion resolveVersion(AnalysisRequest request) {
        if (request.getVersionId() != null) {
            return projectService.getVersion(request.getVersionId());
        }
        ProjectVersion version = projectService.latestReadyVersion(request.getProjectId());
        if (version == null) {
            throw new IllegalStateException("no indexed project version found");
        }
        return version;
    }

    /**
     * 解析请求里的日志文本，抠出线索；堆栈/traceId/时间为空时用日志里的补上。
     */
    private LogClues enrichFromLog(AnalysisRequest request, String logText) {
        LogClues clues = logEvidenceExtractor.extract(logText, request.getApiPath(), request.getUserDescription());
        if (isBlank(request.getStackTrace()) && !isBlank(clues.getStackTrace())) {
            request.setStackTrace(clues.getStackTrace());
        }
        if (isBlank(request.getTraceId()) && !isBlank(clues.getTraceId())) {
            request.setTraceId(clues.getTraceId());
        }
        if (isBlank(request.getRequestTime()) && !isBlank(clues.getRequestTime())) {
            request.setRequestTime(clues.getRequestTime());
        }
        return clues;
    }

    /** 解析本次请求的日志原文：优先粘贴的 logText，没有再按 logId 读上传的日志文件。 */
    private String resolveLogText(AnalysisRequest request) {
        String logText = request.getLogText();
        if (isBlank(logText) && !isBlank(request.getLogId())) {
            logText = logStorageService.read(request.getLogId());
        }
        return logText;
    }

    /**
     * 解析上传日志文件路径，供 search_log 流式读，避免把大日志全文攥进内存。
     * 与 resolveLogText 同一优先级：用户直接粘贴了 logText 时返回 null（贴文本优先，不读文件）。
     */
    private String resolveLogPath(AnalysisRequest request) {
        if (!isBlank(request.getLogText()) || isBlank(request.getLogId())) {
            return null;
        }
        return logStorageService.resolvePath(request.getLogId());
    }

    /** 读过代码或看过表结构的动作，达成后才解锁查库（query_database）。 */
    private boolean unlocksVerify(String action) {
        return "get_code_detail".equals(action) || "trace_call_chain".equals(action)
                || "search_code".equals(action) || "describe_tables".equals(action);
    }

    /** 子 Agent 已读过源码或表结构时，主 Agent 直接进入验证阶段，不为解锁查库重复取证。 */
    private boolean unlocksVerification(List<Map<String, Object>> rounds) {
        if (rounds == null) {
            return false;
        }
        for (Map<String, Object> round : rounds) {
            if (Boolean.TRUE.equals(round.get("toolOk")) && unlocksVerify(safe(round.get("action")))) {
                return true;
            }
        }
        return false;
    }

    /** 拆分截图路径：存储按换行拼接，顺带兼容逗号分隔，去空。 */
    private List<String> parseScreenshotPaths(String screenshotPaths) {
        List<String> paths = new ArrayList<String>();
        if (isBlank(screenshotPaths)) {
            return paths;
        }
        for (String path : screenshotPaths.split("[\\r\\n,]+")) {
            if (!isBlank(path)) {
                paths.add(path.trim());
            }
        }
        return paths;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /** 把本轮所有工具调用的动作名拼成日志串，便于一眼看清这轮查了啥。 */
    private String actionNames(List<AgentToolCall> calls) {
        List<String> names = new ArrayList<String>();
        for (AgentToolCall call : calls) {
            names.add(safe(call.getAction()));
        }
        return String.join(",", names);
    }

    /** 把命中的历史案例接口路径拼成日志串，便于排查带进来哪些参考。 */
    private String similarPaths(List<SimilarCase> cases) {
        List<String> paths = new ArrayList<String>();
        for (SimilarCase similar : cases) {
            paths.add(similar.getApiPath());
        }
        return String.join(", ", paths);
    }
}
