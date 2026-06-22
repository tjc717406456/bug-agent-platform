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
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ToolFanoutExecutor toolFanoutExecutor;
    private final AnalysisRecordRepository recordRepository;
    private final AgentConversation conversation;
    private final AgentRoundReporter reporter;
    private final AgentConvergenceJudge judge;
    private final SimilarCaseRetriever similarCaseRetriever;
    private final HypothesisScout hypothesisScout;
    private final HypothesisFanoutExecutor hypothesisFanoutExecutor;

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
                                ToolFanoutExecutor toolFanoutExecutor,
                                AnalysisRecordRepository recordRepository,
                                AgentConversation conversation,
                                AgentRoundReporter reporter,
                                AgentConvergenceJudge judge,
                                SimilarCaseRetriever similarCaseRetriever,
                                HypothesisScout hypothesisScout,
                                HypothesisFanoutExecutor hypothesisFanoutExecutor) {
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
        this.toolFanoutExecutor = toolFanoutExecutor;
        this.recordRepository = recordRepository;
        this.conversation = conversation;
        this.reporter = reporter;
        this.judge = judge;
        this.similarCaseRetriever = similarCaseRetriever;
        this.hypothesisScout = hypothesisScout;
        this.hypothesisFanoutExecutor = hypothesisFanoutExecutor;
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
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        ProjectDatasource datasource = projectService.firstEnabledDatasource(request.getProjectId());
        // 日志原文解析一次，既喂给上下文供 search_log 深挖，也交给线索抽取
        String logText = resolveLogText(request);
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                request.getProjectId(), version.getId(), request.getApiPath(), datasource, logText);

        // 有日志就先抠出堆栈、SQL、traceId、时间，缺啥补啥，让后续流程自动用上
        LogClues logClues = enrichFromLog(request, logText);
        String initialEvidence = initialEvidenceBuilder.buildInitialEvidence(request, version, graph, datasource, toolContext, logClues);
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

        String finalReport = winner.report;
        String evidence = reporter.buildEvidenceLog(initialEvidence, winner.rounds);
        String confidence = judge.resolveConfidence(graph, winner.rounds, finalReport);
        // 确定性结论连库自动核对，验证通过的无需人工即可进飞轮
        AnalysisAutoVerifier.Result autoVerify = analysisAutoVerifier.verify(safe(finalReport) + "\n" + evidence, datasource);
        Long recordId = recordRepository.save(request, version, finalReport, confidence, evidence, autoVerify);

        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setConfidence(confidence);
        result.setEvidenceJson(evidence);
        result.setAutoVerify(autoVerify.getStatus());
        return result;
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
        String mode = resolveHypothesisMode(request);
        if ("OFF".equals(mode)) {
            return runChain(null, maxIterations, graph, toolContext, initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "");
        }
        List<Hypothesis> hypotheses = hypothesisScout.scout(initialEvidence);
        boolean fanout = hypotheses.size() >= 2 && ("ON".equals(mode) || isAmbiguous(hypotheses));
        if (!fanout) {
            if (!hypotheses.isEmpty()) {
                progress.onStep("侦察完成 · 根因方向明确，单链深挖");
            }
            return runChain(null, maxIterations, graph, toolContext, initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "");
        }

        int branches = Math.min(appProperties.getAgent().getHypothesisMaxBranches(), hypotheses.size());
        int chainIterations = appProperties.getAgent().getHypothesisChainIterations();
        List<Hypothesis> chosen = hypotheses.subList(0, branches);
        progress.onStep("侦察完成 · 发现 " + branches + " 个相近根因，并行验证");
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
            if (result != null && !isBlank(result.report)) {
                ok.add(result);
            }
        }
        if (ok.isEmpty()) {
            progress.onStep("假设分支均未产出，退回单链兜底");
            return runChain(null, maxIterations, graph, toolContext, initialEvidence, initialUserPrompt, screenshots, refPrompt, progress, "");
        }
        if (ok.size() == 1) {
            return ok.get(0);
        }
        return synthesize(ok, progress);
    }

    /** per-request 的 deepMode 优先：true 强制多假设、false 强制单链；否则用全局配置。 */
    private String resolveHypothesisMode(AnalysisRequest request) {
        if (Boolean.TRUE.equals(request.getDeepMode())) {
            return "ON";
        }
        if (Boolean.FALSE.equals(request.getDeepMode())) {
            return "OFF";
        }
        String mode = safe(appProperties.getAgent().getHypothesisMode()).trim().toUpperCase();
        return mode.isEmpty() ? "OFF" : mode;
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
     * 汇总失败就退回查得最透（轮次最多）的那条分支，证据日志合并所有分支供追溯。
     */
    private ChainResult synthesize(List<ChainResult> branches, AnalysisProgressListener progress) {
        progress.onStep("汇总各假设结论，挑出证据最硬的根因");
        StringBuilder reports = new StringBuilder();
        List<Map<String, Object>> mergedRounds = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < branches.size(); i++) {
            reports.append("【假设").append(i + 1).append("的调查结论】\n").append(safe(branches.get(i).report)).append("\n\n");
            mergedRounds.addAll(branches.get(i).rounds);
        }
        String synthesized;
        try {
            synthesized = aiClient.chat(promptBuilder.buildHypothesisSynthesisPrompt(reports.toString()));
        } catch (Exception exception) {
            synthesized = null;
        }
        if (isBlank(synthesized) || synthesized.startsWith("AI")) {
            ChainResult best = branches.get(0);
            for (ChainResult branch : branches) {
                if (branch.rounds.size() > best.rounds.size()) {
                    best = branch;
                }
            }
            return best;
        }
        return new ChainResult(synthesized, mergedRounds);
    }

    /**
     * 一条完整调查链：自建对话、跑多轮工具循环、收口产出报告，返回结果不落库（落库交给编排层统一做）。
     * hypothesisHint 非空时让本链聚焦核验该假设。整套收敛、纠偏、自检、兜底逻辑与单链一致。
     */
    private ChainResult runChain(String hypothesisHint, int maxIterations, CodeGraphQueryResult graph,
                                 AgentToolExecutor.AgentToolContext toolContext, String initialEvidence,
                                 String initialUserPrompt, List<String> screenshots, String refPrompt,
                                 AnalysisProgressListener progress, String label) {
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

        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        String finalReport = null;
        int continuousFailures = 0;
        int noNewKeyFactRounds = 0;
        int toolNudges = 0;
        boolean selfChecked = false;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            String forceFinishReason = judge.resolveForceFinishReason(graph, rounds, noNewKeyFactRounds);
            if (!isBlank(forceFinishReason)) {
                messages.add(conversation.message("user", promptBuilder.buildForceFinishInstruction(forceFinishReason)));
            }
            AiToolCallResult aiResult = aiClient.chatWithMessages(messages, agentToolExecutor.toolSchemas());
            List<AgentToolCall> calls = toolCallParser.parseToolCalls(aiResult);

            // 强制收口：本轮只允许 finish
            AgentToolCall finish = toolCallParser.findFinish(calls);
            if (!isBlank(forceFinishReason) && finish == null) {
                finish = toolCallParser.finishCall(reporter.buildForcedFinishReport(forceFinishReason, rounds));
            }
            if (finish != null) {
                // 模型只用文字描述计划、没真发工具调用：先纠偏逼它走 tool_calls，几次无效再收口
                if (finish.isDegraded() && isBlank(forceFinishReason) && toolNudges < MAX_TOOL_NUDGES) {
                    toolNudges++;
                    conversation.appendAssistantMessage(messages, aiResult);
                    messages.add(conversation.message("user", promptBuilder.buildToolNudge()));
                    progress.onStep(label + "第" + iteration + "轮 · 模型只给了计划，提示其直接调用工具");
                    continue;
                }
                // 模型自然收口（非强制、非兜底）时，先做一次对抗式自检，抓自洽幻觉
                boolean canCritique = appProperties.getAgent().isSelfCritique()
                        && !selfChecked && isBlank(forceFinishReason) && finish.getToolCallId() != null;
                if (canCritique) {
                    selfChecked = true;
                    String report = finish.stringArg("report");
                    String verdict = judge.runSelfCritique(report, rounds, initialEvidence);
                    if (judge.isReviseVerdict(verdict)) {
                        // 复核未通过：闭合本次全部 tool_call，把质疑塞回对话，要求补证后再收
                        conversation.appendAssistantMessage(messages, aiResult);
                        conversation.appendFinishHold(messages, aiResult, finish);
                        messages.add(conversation.message("user", "对你的初步结论做了独立复核，未通过：" + judge.reviseReason(verdict)
                                + " 请针对这一点补查证据，确认后再调用 finish。"));
                        rounds.add(reporter.recordCritique(iteration, report, verdict));
                        progress.onStep(label + "第" + iteration + "轮 · 结论自检未通过，继续补证");
                        continue;
                    }
                }
                progress.onStep(label + "证据足够，正在生成最终报告");
                AgentToolResult finishResult = agentToolExecutor.execute(finish, toolContext);
                rounds.add(reporter.recordRound(iteration, aiResult, finish, finishResult));
                finalReport = finishResult.getEvidence();
                break;
            }

            // 一轮内的多个查证工具并行执行，减少串行 LLM 往返
            List<AgentToolResult> results = toolFanoutExecutor.executeAll(calls, toolContext);
            // 先回填 assistant 的 tool_calls，再逐条回填 tool 结果，保证下一轮请求合法
            conversation.appendAssistantMessage(messages, aiResult);
            conversation.appendToolMessages(messages, aiResult, calls, results);
            boolean anyOk = false;
            boolean anyNewFact = false;
            boolean anyHardFailure = false;
            for (int i = 0; i < calls.size(); i++) {
                AgentToolCall call = calls.get(i);
                AgentToolResult toolResult = results.get(i);
                boolean newKeyFact = judge.isNewKeyFact(rounds, call, toolResult);
                rounds.add(reporter.recordRound(iteration, aiResult, call, toolResult));
                progress.onStep(label + "第" + iteration + "轮 · " + reporter.actionName(call.getAction()) + " · " + trim(safe(toolResult.getSummary()), 40));
                anyOk = anyOk || toolResult.isOk();
                anyNewFact = anyNewFact || newKeyFact;
                anyHardFailure = anyHardFailure || toolResult.isHardFailure();
            }

            if (anyOk) {
                continuousFailures = 0;
                noNewKeyFactRounds = anyNewFact ? 0 : noNewKeyFactRounds + 1;
            } else if (anyHardFailure) {
                // 真错误(参数/数据源/异常)才计入掐断
                continuousFailures++;
            } else {
                // 整轮都是"查无结果"：正常探索，不算失败；但没新事实，推进收敛兜底防空转
                noNewKeyFactRounds++;
            }
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                finalReport = reporter.buildFailureReport(rounds);
                break;
            }
        }

        if (finalReport == null) {
            // 到顶仍没收口：再逼模型基于已有证据产出一份完整报告，别甩一句话
            finalReport = forceSynthesizeReport(messages, toolContext, rounds, maxIterations + 1);
        }
        return new ChainResult(finalReport, rounds);
    }

    /** 一条调查链的产出：最终报告 + 全部轮次记录，不含落库。 */
    private static final class ChainResult {
        private final String report;
        private final List<Map<String, Object>> rounds;

        private ChainResult(String report, List<Map<String, Object>> rounds) {
            this.report = report;
            this.rounds = rounds;
        }
    }

    /**
     * 达到最大轮次仍没收口时，再发一次请求逼模型基于已有证据 finish 出完整报告；
     * 模型仍不配合才退回本地汇总报告，保证给到的不是一句话。
     */
    private String forceSynthesizeReport(List<Map<String, Object>> messages, AgentToolExecutor.AgentToolContext toolContext,
                                         List<Map<String, Object>> rounds, int iteration) {
        try {
            messages.add(conversation.message("user", "已达到最大分析轮次。现在必须基于以上全部证据调用 finish 输出最终报告，"
                    + "report 要包含：通俗结论、问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度；"
                    + "证据不足就给出最可能的判断并说明剩余风险，不要再调用查证工具。"));
            AiToolCallResult aiResult = aiClient.chatWithMessages(messages, agentToolExecutor.toolSchemas());
            AgentToolCall finish = toolCallParser.findFinish(toolCallParser.parseToolCalls(aiResult));
            if (finish != null && !isBlank(finish.stringArg("report"))) {
                AgentToolResult finishResult = agentToolExecutor.execute(finish, toolContext);
                rounds.add(reporter.recordRound(iteration, aiResult, finish, finishResult));
                return finishResult.getEvidence();
            }
        } catch (Exception exception) {
            // 兜底报告照样给，不让异常打断收尾
        }
        return reporter.buildMaxRoundReport(rounds);
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

    /** 把命中的历史案例接口路径拼成日志串，便于排查带进来哪些参考。 */
    private String similarPaths(List<SimilarCase> cases) {
        List<String> paths = new ArrayList<String>();
        for (SimilarCase similar : cases) {
            paths.add(similar.getApiPath());
        }
        return String.join(", ", paths);
    }
}
