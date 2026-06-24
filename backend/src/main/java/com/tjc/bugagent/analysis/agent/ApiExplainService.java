package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;
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
                             AppProperties appProperties) {
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
    }

    public AnalysisResult explain(AnalysisRequest request) {
        return explain(request, AnalysisProgressListener.NOOP);
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
        int tokens = 0;
        int maxIterations = appProperties.getAgent().getMaxIterations();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            AiToolCallResult aiResult = aiClient.chatWithMessagesUtility(messages, agentToolExecutor.toolSchemas());
            tokens += aiResult.getTotalTokens();
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
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                finalReport = reporter.buildFailureReport(rounds);
                break;
            }
        }

        if (finalReport == null) {
            finalReport = forceSynthesizeExplain(messages, toolContext, rounds, maxIterations + 1);
        }

        String evidence = reporter.buildEvidenceLog(initialEvidence, rounds);
        AnalysisResult result = new AnalysisResult();
        result.setPlainAnswer(plainAnswerResolver.buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setEvidenceJson(evidence);
        result.setTotalTokens(tokens);
        result.setElapsedMs(System.currentTimeMillis() - startMs);
        progress.onStep("✓ 讲解完成 · " + rounds.size() + " 轮查证 · " + tokens + " tokens · " + (result.getElapsedMs() / 1000) + "s");
        return result;
    }

    /**
     * 到顶仍没收口时，逼模型基于已有证据 finish 出完整讲解，不配合才退回本地汇总。
     */
    private String forceSynthesizeExplain(List<Map<String, Object>> messages, AgentToolExecutor.AgentToolContext toolContext,
                                          List<Map<String, Object>> rounds, int iteration) {
        try {
            messages.add(conversation.message("user", "已达到最大轮次。现在必须基于以上全部证据调用 finish 输出接口讲解，"
                    + "report 要包含：通俗说明、完整流程（入口→服务→Mapper→SQL→表）、关键代码片段、涉及的表与数据流向；不要再调用查证工具。"));
            AiToolCallResult aiResult = aiClient.chatWithMessagesUtility(messages, agentToolExecutor.toolSchemas());
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
}
