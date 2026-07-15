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
import com.tjc.bugagent.project.DatasourceSelection;
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
    private final AgentConversation conversation;
    private final AgentToolCallParser toolCallParser;
    private final AgentRoundReporter reporter;
    private final AgentPromptBuilder promptBuilder;
    private final PlainAnswerResolver plainAnswerResolver;
    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final AnalysisRecordRepository recordRepository;
    private final AgentRunner agentRunner;

    public ApiExplainService(ProjectService projectService,
                             CodeGraphQueryService codeGraphQueryService,
                             InitialEvidenceBuilder initialEvidenceBuilder,
                             AgentToolExecutor agentToolExecutor,
                             AgentConversation conversation,
                             AgentToolCallParser toolCallParser,
                             AgentRoundReporter reporter,
                             AgentPromptBuilder promptBuilder,
                             PlainAnswerResolver plainAnswerResolver,
                             AiClient aiClient,
                             AppProperties appProperties,
                             AnalysisRecordRepository recordRepository,
                             AgentRunner agentRunner) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.initialEvidenceBuilder = initialEvidenceBuilder;
        this.agentToolExecutor = agentToolExecutor;
        this.conversation = conversation;
        this.toolCallParser = toolCallParser;
        this.reporter = reporter;
        this.promptBuilder = promptBuilder;
        this.plainAnswerResolver = plainAnswerResolver;
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.recordRepository = recordRepository;
        this.agentRunner = agentRunner;
    }

    /**
     * 带进度回调的接口讲解，多轮工具循环驱动模型下钻调用链和源码。
     */
    public AnalysisResult explain(AnalysisRequest request, AnalysisProgressListener progress) {
        long startMs = System.currentTimeMillis();
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        DatasourceSelection datasourceSelection = projectService.resolveDatasourceSelection(
                request.getProjectId(), request.getEnvironment(), request.getDatabasePolicy());
        request.setEnvironment(datasourceSelection.getEnvironment());
        request.setDatabaseAccessLevel(datasourceSelection.getAccessLevel());
        ProjectExecutionScope parentScope = progress.executionScope(
                request.getProjectId(), version.getId(), datasourceSelection);
        String explainTaskId = isBlank(parentScope.getTaskId()) ? "api-explain" : parentScope.getTaskId() + ":explain";
        ProjectExecutionScope explainScope = parentScope.child(
                explainTaskId, "search_code", "get_code_detail", "trace_call_chain",
                "search_sql", "grep_source", "find_callers", "describe_tables",
                "query_database", "finish");
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                explainScope,
                request.getApiPath(), null, null);

        // 讲解不需要日志/堆栈，给个空线索，初始证据按"有才拼"自动跳过这些段落
        String initialEvidence = initialEvidenceBuilder.buildInitialEvidence(request, version, graph, toolContext, new LogClues());
        log.info("接口讲解开始 projectId={} versionId={} apiPath={} 命中路由{}条",
                request.getProjectId(), version.getId(), request.getApiPath(), graph.getRouteNodes().size());

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system", promptBuilder.buildApiExplainSystemPrompt()));
        messages.add(conversation.message("user", promptBuilder.buildApiExplainUserPrompt(initialEvidence)));
        progress.onStep("已取接口调用链与源码，开始讲解");

        final List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        final int maxIterations = appProperties.getAgent().getExplainMaxIterations();
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(messages);
        spec.setToolContext(toolContext);
        spec.setMaxIterations(maxIterations);
        spec.setKeepRecentRounds(appProperties.getAgent().getKeepRecentRounds());
        spec.setVerificationEnabled(true);
        spec.setModelRole(AgentRunSpec.ModelRole.UTILITY);
        spec.setProgress(progress);
        spec.setModelFailureMessage("⚠️ AI 服务暂不可用（网关连接异常/重置），本次讲解中断，请稍后重试。");
        spec.setPolicy(new ApiExplainRunPolicy(rounds, progress, maxIterations));
        AgentRunResult runResult = agentRunner.run(spec);
        String finalReport = runResult.getFinalContent();

        String evidence = reporter.buildEvidenceLog(initialEvidence, rounds);
        // 讲解也落库（record_type=EXPLAIN）：历史可回看，且拿到 recordId 就能追问
        Long recordId = recordRepository.saveExplain(request, version, finalReport, evidence,
                runResult.getIterations(), runResult.getTotalTokens());
        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setEvidenceJson(evidence);
        result.setRounds(runResult.getIterations());
        result.setTotalTokens(runResult.getTotalTokens());
        result.setElapsedMs(System.currentTimeMillis() - startMs);
        progress.onStep("✓ 讲解完成 · " + runResult.getIterations() + " 轮查证 · "
                + runResult.getTotalTokens() + " tokens · " + (result.getElapsedMs() / 1000) + "s");
        return result;
    }

    /** 接口讲解的领域策略：保留纠偏、失败掐断和两段式报告，迭代骨架交给 AgentRunner。 */
    private final class ApiExplainRunPolicy implements AgentRunPolicy {
        private final List<Map<String, Object>> rounds;
        private final AnalysisProgressListener progress;
        private final int maxIterations;
        private int continuousFailures;
        private int toolNudges;
        private boolean budgetWarned;

        private ApiExplainRunPolicy(List<Map<String, Object>> rounds, AnalysisProgressListener progress, int maxIterations) {
            this.rounds = rounds;
            this.progress = progress;
            this.maxIterations = maxIterations;
        }

        public void beforeIteration(AgentRunContext context) {
            int warnFrom = Math.max(1, maxIterations * 2 / 3);
            if (!budgetWarned && context.getIteration() >= warnFrom) {
                budgetWarned = true;
                context.getMessages().add(conversation.message("user",
                        promptBuilder.buildBudgetReminder(context.getIteration(), maxIterations)));
            }
        }

        public void afterModelResponse(AgentRunContext context, AiToolCallResult response) {
            if (!isBlank(response.getContent())) {
                log.info("第{}轮 · 思考: {}", context.getIteration(), trim(safe(response.getContent()), 200));
            }
        }

        public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult response, AgentToolCall finish) {
            int iteration = context.getIteration();
            if (finish.isDegraded() && toolNudges < MAX_TOOL_NUDGES) {
                toolNudges++;
                conversation.appendAssistantMessage(context.getMessages(), response);
                context.getMessages().add(conversation.message("user", promptBuilder.buildToolNudge()));
                progress.onStep("第" + iteration + "轮 · 模型只给了计划，提示其直接调用工具");
                return AgentRunDirective.continueRun();
            }
            String report = finish.stringArg("report");
            if (safe(report).trim().length() < 300 && finish.getToolCallId() != null) {
                report = streamExplain(context, response, finish);
                if (isBlank(report)) {
                    return AgentRunDirective.stop(null, AgentStopReason.TOOL_ERROR);
                }
                rounds.add(reporter.recordRound(iteration, response, finish,
                        AgentToolResult.ok("finish", "收口信号，讲解已流式生成", report)));
                return AgentRunDirective.stop(report, AgentStopReason.FINISH_TOOL);
            }
            progress.onStep("流程已讲清，正在生成说明");
            AgentToolResult finishResult = agentToolExecutor.execute(finish, context.getToolContext());
            rounds.add(reporter.recordRound(iteration, response, finish, finishResult));
            return AgentRunDirective.stop(finishResult.getEvidence(), AgentStopReason.FINISH_TOOL);
        }

        public AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult response,
                                            List<AgentToolCall> calls, List<AgentToolResult> results) {
            boolean anyOk = false;
            boolean anyHardFailure = false;
            for (int index = 0; index < calls.size(); index++) {
                AgentToolCall call = calls.get(index);
                AgentToolResult result = results.get(index);
                rounds.add(reporter.recordRound(context.getIteration(), response, call, result));
                progress.onStep("第" + context.getIteration() + "轮 · " + reporter.actionName(call.getAction())
                        + " · " + trim(safe(result.getSummary()), 40));
                anyOk = anyOk || result.isOk();
                anyHardFailure = anyHardFailure || result.isHardFailure();
            }
            if (anyOk) {
                continuousFailures = 0;
            } else if (anyHardFailure) {
                continuousFailures++;
            }
            log.info("第{}轮 · 动作=[{}] · 结果={} · 失败={}/{}", context.getIteration(), actionNames(calls),
                    anyOk ? "OK" : (anyHardFailure ? "FAIL" : "空"), continuousFailures, MAX_CONTINUOUS_FAILURES);
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                return AgentRunDirective.stop(reporter.buildFailureReport(rounds), AgentStopReason.CONTINUOUS_TOOL_FAILURES);
            }
            return AgentRunDirective.continueRun();
        }

        public String finalizeRun(AgentRunContext context) {
            AtomicInteger tokens = new AtomicInteger();
            String report = forceSynthesizeExplain(context.getMessages(), context.getToolContext(), rounds,
                    context.getIteration() + 1, tokens, progress);
            context.addTokens(tokens.get());
            return report;
        }

        public Map<String, Object> snapshotState(AgentRunContext context) {
            Map<String, Object> state = new java.util.LinkedHashMap<String, Object>();
            state.put("rounds", new ArrayList<Map<String, Object>>(rounds));
            state.put("continuousFailures", continuousFailures);
            state.put("toolNudges", toolNudges);
            state.put("budgetWarned", budgetWarned);
            return state;
        }

        public void restoreState(AgentRunContext context, Map<String, Object> state) {
            rounds.clear();
            rounds.addAll(AgentRunStateUtils.rounds(state));
            continuousFailures = AgentRunStateUtils.intValue(state, "continuousFailures");
            toolNudges = AgentRunStateUtils.intValue(state, "toolNudges");
            budgetWarned = AgentRunStateUtils.boolValue(state, "budgetWarned");
        }

        private String streamExplain(AgentRunContext context, AiToolCallResult response, AgentToolCall finish) {
            progress.onStep("流程已讲清，开始生成说明");
            conversation.appendAssistantMessage(context.getMessages(), response);
            conversation.appendFinishAck(context.getMessages(), response, finish);
            context.getMessages().add(conversation.message("user", "现在不要调用任何工具，直接用纯文字写出最终接口讲解，"
                    + "包含：通俗说明、完整流程（入口→服务→Mapper→SQL→表）、关键代码片段（带文件:行号）、涉及的表与数据流向。"));
            final long[] lastPushMs = {0};
            AiToolCallResult prose = aiClient.chatProseStreamUtility(context.getMessages(), snapshot -> {
                long now = System.currentTimeMillis();
                if (now - lastPushMs[0] >= 400) {
                    lastPushMs[0] = now;
                    progress.onPartialReport(snapshot);
                }
            });
            context.addTokens(prose.getTotalTokens());
            if (prose.isFailed() || isBlank(prose.getContent())) {
                return null;
            }
            progress.onPartialReport(prose.getContent());
            return prose.getContent();
        }
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
        return buildExplainFallbackReport(rounds);
    }

    /** 接口讲解专用兜底，不混入 Bug 修复建议和置信度等定位报告字段。 */
    private String buildExplainFallbackReport(List<Map<String, Object>> rounds) {
        StringBuilder report = new StringBuilder();
        report.append("【通俗说明】接口讲解达到最大轮次，以下内容基于已经读取到的调用链和源码整理。\n\n");
        report.append("【完整流程】\n");
        if (rounds.isEmpty()) {
            report.append("当前没有取得可用的流程证据。\n");
        } else {
            for (Map<String, Object> round : rounds) {
                report.append("- 第").append(safe(round.get("iteration"))).append("轮 · ")
                        .append(reporter.actionName(safe(round.get("action")))).append("：")
                        .append(trim(safe(round.get("toolSummary")), 100)).append("\n");
            }
        }
        report.append("\n【关键代码】请结合证据页中已读取的源码位置继续确认。\n");
        report.append("【数据流向】未讲透的环节需结合当前版本源码和授权范围内的数据库证据补充。\n");
        return report.toString();
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
