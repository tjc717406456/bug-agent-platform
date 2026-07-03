package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;
import com.tjc.bugagent.analysis.AnalysisRecordRepository;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.AnalysisResult;
import com.tjc.bugagent.analysis.log.LogClues;
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
import java.util.concurrent.atomic.AtomicInteger;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 接口讲解 Agent：只给项目+版本+接口路径，讲清这个接口干什么、完整流程（入口→服务→Mapper→SQL→表）。
 * 复用 Bug 定位那套多轮工具循环和协作类，但砍掉日志/堆栈、收敛判定、自检、连库验证、历史注入、落库这些 bug 专属环节。
 */
@Service
public class ApiExplainService {
    private static final Logger log = LoggerFactory.getLogger(ApiExplainService.class);
    private static final int MAX_CONTINUOUS_FAILURES = 2;
    // 模型只描述计划不发工具调用时，最多纠偏几次再放弃
    private static final int MAX_TOOL_NUDGES = 2;

    private final ProjectService projectService;
    private final CodeGraphQueryService codeGraphQueryService;
    private final InitialEvidenceBuilder initialEvidenceBuilder;
    private final AgentToolExecutor agentToolExecutor;
    private final ToolFanoutExecutor toolFanoutExecutor;
    private final AgentConversation conversation;
    private final AgentToolCallParser toolCallParser;
    private final AgentRoundReporter reporter;
    private final AgentPromptBuilder promptBuilder;
    private final PlainAnswerResolver plainAnswerResolver;
    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final AnalysisRecordRepository recordRepository;

    public ApiExplainService(ProjectService projectService,
                             CodeGraphQueryService codeGraphQueryService,
                             InitialEvidenceBuilder initialEvidenceBuilder,
                             AgentToolExecutor agentToolExecutor,
                             ToolFanoutExecutor toolFanoutExecutor,
                             AgentConversation conversation,
                             AgentToolCallParser toolCallParser,
                             AgentRoundReporter reporter,
                             AgentPromptBuilder promptBuilder,
                             PlainAnswerResolver plainAnswerResolver,
                             AiClient aiClient,
                             AppProperties appProperties,
                             AnalysisRecordRepository recordRepository) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.initialEvidenceBuilder = initialEvidenceBuilder;
        this.agentToolExecutor = agentToolExecutor;
        this.toolFanoutExecutor = toolFanoutExecutor;
        this.conversation = conversation;
        this.toolCallParser = toolCallParser;
        this.reporter = reporter;
        this.promptBuilder = promptBuilder;
        this.plainAnswerResolver = plainAnswerResolver;
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.recordRepository = recordRepository;
    }

    /**
     * 带进度回调的接口讲解，多轮工具循环驱动模型下钻调用链和源码。
     */
    public AnalysisResult explain(AnalysisRequest request, AnalysisProgressListener progress) {
        long startMs = System.currentTimeMillis();
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        ProjectDatasource datasource = projectService.firstEnabledDatasource(request.getProjectId());
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                request.getProjectId(), version.getId(), request.getApiPath(), datasource);

        // 讲解不需要日志/堆栈，给个空线索，初始证据按"有才拼"自动跳过这些段落
        String initialEvidence = initialEvidenceBuilder.buildInitialEvidence(request, version, graph, datasource, toolContext, new LogClues());
        log.info("接口讲解开始 projectId={} versionId={} apiPath={} 命中路由{}条",
                request.getProjectId(), version.getId(), request.getApiPath(), graph.getRouteNodes().size());

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system", promptBuilder.buildApiExplainSystemPrompt()));
        messages.add(conversation.message("user", promptBuilder.buildApiExplainUserPrompt(initialEvidence)));
        progress.onStep("已取接口调用链与源码，开始讲解");

        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        String finalReport = null;
        int continuousFailures = 0;
        int toolNudges = 0;
        // AtomicInteger 当账本传给收尾兜底，那笔 LLM 开销也要入账
        AtomicInteger tokens = new AtomicInteger();
        // 真实循环圈数，展示"轮"用它；rounds 列表按工具调用逐条记，条数≠轮数
        int iterationsRun = 0;
        // 讲解用独立的小轮次预算：没有收敛判定兜底，不能拿 bug 分析的 32 轮裸奔
        int maxIterations = appProperties.getAgent().getExplainMaxIterations();
        boolean budgetWarned = false;
        int budgetWarnFrom = Math.max(1, maxIterations * 2 / 3);

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            iterationsRun = iteration;
            // 用户手动停止：轮间检测到即中断
            if (progress.isCancelled()) {
                throw new AnalysisCancelledException();
            }
            // 过了 2/3 轮提醒模型抓紧收口，别无限下钻无关细节
            if (!budgetWarned && iteration >= budgetWarnFrom) {
                budgetWarned = true;
                messages.add(conversation.message("user", promptBuilder.buildBudgetReminder(iteration, maxIterations)));
            }
            // 发请求前折叠早期轮次工具结果，与 bug 分析同款：肥结果(如全量表结构)不再每轮重发,
            // 治后期轮次上下文膨胀导致的模型响应变慢/读超时。完整证据仍在 rounds[]，讲解报告不受影响
            conversation.decayOldToolMessages(messages, appProperties.getAgent().getKeepRecentRounds());
            AiToolCallResult aiResult = aiClient.chatWithMessagesUtility(messages, agentToolExecutor.toolSchemas());
            tokens.addAndGet(aiResult.getTotalTokens());
            // 与 bug 分析同款：模型每轮思考正文落日志(截断防刷屏)，排查"讲歪了"时有迹可循
            if (!isBlank(aiResult.getContent())) {
                log.info("第{}轮 · 思考: {}", iteration, trim(safe(aiResult.getContent()), 200));
            }
            // AI 真失败（网关重置/未配置）：直接中止，别把"AI不可用"当讲解收口
            if (aiResult.isFailed()) {
                finalReport = "⚠️ AI 服务暂不可用（网关连接异常/重置），本次讲解在第 " + iteration + " 轮中断，请稍后重试。";
                break;
            }
            List<AgentToolCall> calls = toolCallParser.parseToolCalls(aiResult);

            AgentToolCall finish = toolCallParser.findFinish(calls);
            if (finish != null) {
                // 模型只描述计划没真发工具调用：先纠偏逼它走 tool_calls，几次无效再收口
                if (finish.isDegraded() && toolNudges < MAX_TOOL_NUDGES) {
                    toolNudges++;
                    conversation.appendAssistantMessage(messages, aiResult);
                    messages.add(conversation.message("user", promptBuilder.buildToolNudge()));
                    progress.onStep("第" + iteration + "轮 · 模型只给了计划，提示其直接调用工具");
                    continue;
                }
                String argReport = finish.stringArg("report");
                // 概要级长度(<300字)一律当收口信号走流式生成，别把一句话概要当完整讲解收工
                if (safe(argReport).trim().length() < 300 && finish.getToolCallId() != null) {
                    // 两段式收口：finish 只是信号，完整讲解改用纯文字流式生成，前端边生成边看
                    log.info("第{}轮 · 查证结束，开始流式生成最终讲解", iteration);
                    progress.onStep("流程已讲清，开始生成说明");
                    conversation.appendAssistantMessage(messages, aiResult);
                    conversation.appendFinishAck(messages, aiResult, finish);
                    messages.add(conversation.message("user", "现在不要调用任何工具，直接用纯文字写出最终接口讲解，"
                            + "包含：通俗说明、完整流程（入口→服务→Mapper→SQL→表）、关键代码片段（带文件:行号）、涉及的表与数据流向。"));
                    final long[] lastPushMs = {0};
                    AiToolCallResult prose = aiClient.chatProseStreamUtility(messages, snapshot -> {
                        long now = System.currentTimeMillis();
                        // 800ms 节流：前端 2s 轮询一拍，推太密只是白写任务存储
                        if (now - lastPushMs[0] >= 400) {
                            lastPushMs[0] = now;
                            progress.onPartialReport(snapshot);
                        }
                    });
                    tokens.addAndGet(prose.getTotalTokens());
                    if (!prose.isFailed() && !isBlank(prose.getContent())) {
                        progress.onPartialReport(prose.getContent());
                        finalReport = prose.getContent();
                        rounds.add(reporter.recordRound(iteration, aiResult, finish,
                                AgentToolResult.ok("finish", "收口信号，讲解已流式生成", finalReport)));
                    } else {
                        // 流式失败：finalReport 保持 null，落到循环后的 forceSynthesizeExplain 兜底
                        log.warn("第{}轮 · 收口后流式生成讲解失败，转收尾兜底", iteration);
                    }
                    break;
                }
                log.info("第{}轮 · 查证结束，模型已收口，最终讲解生成完毕", iteration);
                progress.onStep("流程已讲清，正在生成说明");
                AgentToolResult finishResult = agentToolExecutor.execute(finish, toolContext);
                rounds.add(reporter.recordRound(iteration, aiResult, finish, finishResult));
                finalReport = finishResult.getEvidence();
                break;
            }

            List<AgentToolResult> results = toolFanoutExecutor.executeAll(calls, toolContext);
            conversation.appendAssistantMessage(messages, aiResult);
            conversation.appendToolMessages(messages, aiResult, calls, results);
            boolean anyOk = false;
            boolean anyHardFailure = false;
            for (int i = 0; i < calls.size(); i++) {
                AgentToolCall call = calls.get(i);
                AgentToolResult toolResult = results.get(i);
                rounds.add(reporter.recordRound(iteration, aiResult, call, toolResult));
                progress.onStep("第" + iteration + "轮 · " + reporter.actionName(call.getAction()) + " · " + trim(safe(toolResult.getSummary()), 40));
                anyOk = anyOk || toolResult.isOk();
                anyHardFailure = anyHardFailure || toolResult.isHardFailure();
            }
            // 查无结果是正常探索，只有真错误才计入掐断
            if (anyOk) {
                continuousFailures = 0;
            } else if (anyHardFailure) {
                continuousFailures++;
            }
            // 每轮一行结构化日志，与 bug 分析同款观测口径（讲解没有新事实/空转判定，只记动作与结果）
            log.info("第{}轮 · 动作=[{}] · 结果={} · 失败={}/{}", iteration, actionNames(calls),
                    anyOk ? "OK" : (anyHardFailure ? "FAIL" : "空"), continuousFailures, MAX_CONTINUOUS_FAILURES);
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                finalReport = reporter.buildFailureReport(rounds);
                break;
            }
        }

        if (finalReport == null) {
            finalReport = forceSynthesizeExplain(messages, toolContext, rounds, maxIterations + 1, tokens, progress);
        }

        String evidence = reporter.buildEvidenceLog(initialEvidence, rounds);
        // 讲解也落库（record_type=EXPLAIN）：历史可回看，且拿到 recordId 就能追问
        Long recordId = recordRepository.saveExplain(request, version, finalReport, evidence, iterationsRun, tokens.get());
        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setEvidenceJson(evidence);
        result.setRounds(iterationsRun);
        result.setTotalTokens(tokens.get());
        result.setElapsedMs(System.currentTimeMillis() - startMs);
        progress.onStep("✓ 讲解完成 · " + iterationsRun + " 轮查证 · " + tokens.get() + " tokens · " + (result.getElapsedMs() / 1000) + "s");
        return result;
    }

    /**
     * 到顶仍没收口时的收尾：不再要求模型把全文塞进 finish 参数（模型常只给一句概要糊弄），
     * 直接纯文字流式生成完整讲解，前端同样能渐进看到；失败退回本地汇总。
     */
    private String forceSynthesizeExplain(List<Map<String, Object>> messages, AgentToolExecutor.AgentToolContext toolContext,
                                          List<Map<String, Object>> rounds, int iteration, AtomicInteger tokenSink,
                                          AnalysisProgressListener progress) {
        try {
            log.info("已达最大轮次({})，查证结束，开始流式生成最终讲解", iteration - 1);
            progress.onStep("已达最大轮次，开始生成说明");
            messages.add(conversation.message("user", "已达到最大轮次。现在不要调用任何工具，直接用纯文字写出最终接口讲解，"
                    + "包含：通俗说明、完整流程（入口→服务→Mapper→SQL→表）、关键代码片段（带文件:行号）、涉及的表与数据流向；"
                    + "有讲不透的环节就如实说明。"));
            final long[] lastPushMs = {0};
            AiToolCallResult prose = aiClient.chatProseStreamUtility(messages, snapshot -> {
                long now = System.currentTimeMillis();
                if (now - lastPushMs[0] >= 400) {
                    lastPushMs[0] = now;
                    progress.onPartialReport(snapshot);
                }
            });
            tokenSink.addAndGet(prose.getTotalTokens());
            if (!prose.isFailed() && !isBlank(prose.getContent())) {
                progress.onPartialReport(prose.getContent());
                rounds.add(reporter.recordRound(iteration, prose, toolCallParser.finishCall(prose.getContent()),
                        AgentToolResult.ok("finish", "已达最大轮次，讲解以纯文字流式生成", prose.getContent())));
                return prose.getContent();
            }
        } catch (Exception exception) {
            // 兜底报告照样给，不让异常打断收尾
        }
        return reporter.buildMaxRoundReport(rounds);
    }

    /** 本轮全部工具动作名拼成日志串，一眼看清这轮查了啥。 */
    private String actionNames(List<AgentToolCall> calls) {
        List<String> names = new ArrayList<String>();
        for (AgentToolCall call : calls) {
            names.add(safe(call.getAction()));
        }
        return String.join(",", names);
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
}
