package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 只读 Bug 定位 Agent，围绕代码图谱、SQL 和数据库证据做多轮分析。
 */
@Service
public class AgentAnalysisService {
    private static final int MAX_CONTINUOUS_FAILURES = 2;
    private static final int MAX_NO_NEW_FACT_ROUNDS = 2;
    private static final int SNAPSHOT_RELATED_LIMIT = 4;
    private static final int SNAPSHOT_SNIPPET_LINES = 30;
    // 异常堆栈定位：取栈顶前几个业务栈帧，各读一小段源码
    private static final int STACK_FRAME_LIMIT = 3;
    private static final int STACK_SNIPPET_LINES = 24;
    private static final Pattern UNKNOWN_COLUMN_PATTERN = Pattern.compile("Unknown column '([^']+)'");
    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([`\\w.]+)");
    // 常见确定性错误的识别模式，命中后直接给运维/测试/实施能看懂的人话
    private static final Pattern DATA_TOO_LONG_PATTERN = Pattern.compile("Data too long for column '([^']+)'");
    private static final Pattern DATA_TRUNCATED_PATTERN = Pattern.compile("Data truncated for column '([^']+)'");
    private static final Pattern COLUMN_NULL_PATTERN = Pattern.compile("Column '([^']+)' cannot be null");
    private static final Pattern NO_DEFAULT_PATTERN = Pattern.compile("Field '([^']+)' doesn't have a default value");
    private static final Pattern DUPLICATE_ENTRY_PATTERN = Pattern.compile("Duplicate entry '([^']*)' for key '([^']+)'");
    private static final Pattern TABLE_MISSING_PATTERN = Pattern.compile("Table '([^']+)' doesn't exist");
    private static final Pattern INCORRECT_VALUE_PATTERN = Pattern.compile("Incorrect (\\w+) value: '([^']*)'");
    private static final Pattern SQL_SYNTAX_PATTERN = Pattern.compile("You have an error in your SQL syntax");
    // 匹配 "at com.x.Y.method(Y.java:123)" 形式的栈帧
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile("at\\s+([\\w.$]+)\\.([\\w$<>]+)\\([^)]*?:(\\d+)\\)");
    // 框架/JDK 包前缀，这些栈帧对定位业务 bug 没价值，跳过
    private static final String[] STACK_SKIP_PREFIXES = {
            "java.", "javax.", "jakarta.", "sun.", "jdk.", "com.sun.",
            "org.springframework.", "org.apache.", "org.mybatis.", "com.baomidou.",
            "org.hibernate.", "com.mysql.", "com.zaxxer.", "com.fasterxml.",
            "ch.qos.", "org.slf4j.", "io.netty.", "reactor.", "org.junit."
    };

    private final ProjectService projectService;
    private final CodeGraphQueryService codeGraphQueryService;
    private final AgentToolExecutor agentToolExecutor;
    private final AiClient aiClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final LogEvidenceExtractor logEvidenceExtractor;
    // 一轮内多个查证工具并行执行用的小型线程池（非 Spring @Async，避免抢占分析任务线程池）
    private final ExecutorService toolFanoutPool = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "agent-tool-fanout");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        toolFanoutPool.shutdownNow();
    }

    public AgentAnalysisService(ProjectService projectService,
                                CodeGraphQueryService codeGraphQueryService,
                                AgentToolExecutor agentToolExecutor,
                                AiClient aiClient,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                AppProperties appProperties,
                                LogEvidenceExtractor logEvidenceExtractor) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.agentToolExecutor = agentToolExecutor;
        this.aiClient = aiClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.logEvidenceExtractor = logEvidenceExtractor;
    }

    /**
     * 执行 Agent 多轮分析并保存分析记录。
     */
    public AnalysisResult analyze(AnalysisRequest request) {
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        ProjectDatasource datasource = projectService.firstEnabledDatasource(request.getProjectId());
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                request.getProjectId(), version.getId(), request.getApiPath(), datasource);

        // 有日志就先抠出堆栈、SQL、traceId、时间，缺啥补啥，让后续流程自动用上
        LogClues logClues = enrichFromLog(request);
        String initialEvidence = buildInitialEvidence(request, version, graph, datasource, toolContext, logClues);
        // 维护一条完整 OpenAI 对话，历轮工具结果按协议回填，模型每轮都能看到上下文全貌
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", buildSystemPrompt()));
        messages.add(message("user", buildInitialUserPrompt(initialEvidence)));

        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        String finalReport = null;
        int continuousFailures = 0;
        int noNewKeyFactRounds = 0;
        boolean selfChecked = false;

        int maxIterations = appProperties.getAgent().getMaxIterations();
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            String forceFinishReason = resolveForceFinishReason(graph, rounds, noNewKeyFactRounds);
            if (!isBlank(forceFinishReason)) {
                messages.add(message("user", buildForceFinishInstruction(forceFinishReason)));
            }
            AiToolCallResult aiResult = aiClient.chatWithMessages(messages, agentToolExecutor.toolSchemas());
            List<AgentToolCall> calls = parseToolCalls(aiResult);

            // 强制收口：本轮只允许 finish
            AgentToolCall finish = findFinish(calls);
            if (!isBlank(forceFinishReason) && finish == null) {
                finish = finishCall(buildForcedFinishReport(forceFinishReason, rounds));
            }
            if (finish != null) {
                // 模型自然收口（非强制、非兜底）时，先做一次对抗式自检，抓自洽幻觉
                boolean canCritique = appProperties.getAgent().isSelfCritique()
                        && !selfChecked && isBlank(forceFinishReason) && finish.getToolCallId() != null;
                if (canCritique) {
                    selfChecked = true;
                    String report = finish.stringArg("report");
                    String verdict = runSelfCritique(report, rounds, initialEvidence);
                    if (isReviseVerdict(verdict)) {
                        // 复核未通过：闭合本次全部 tool_call，把质疑塞回对话，要求补证后再收
                        appendAssistantMessage(messages, aiResult);
                        appendFinishHold(messages, aiResult, finish);
                        messages.add(message("user", "对你的初步结论做了独立复核，未通过：" + reviseReason(verdict)
                                + " 请针对这一点补查证据，确认后再调用 finish。"));
                        rounds.add(recordCritique(iteration, report, verdict));
                        continue;
                    }
                }
                AgentToolResult finishResult = agentToolExecutor.execute(finish, toolContext);
                rounds.add(recordRound(iteration, aiResult, finish, finishResult));
                finalReport = finishResult.getEvidence();
                break;
            }

            // 一轮内的多个查证工具并行执行，减少串行 LLM 往返
            List<AgentToolResult> results = executeAll(calls, toolContext);
            // 先回填 assistant 的 tool_calls，再逐条回填 tool 结果，保证下一轮请求合法
            appendAssistantMessage(messages, aiResult);
            appendToolMessages(messages, aiResult, calls, results);
            boolean anyOk = false;
            boolean anyNewFact = false;
            for (int i = 0; i < calls.size(); i++) {
                AgentToolCall call = calls.get(i);
                AgentToolResult toolResult = results.get(i);
                boolean newKeyFact = isNewKeyFact(rounds, call, toolResult);
                rounds.add(recordRound(iteration, aiResult, call, toolResult));
                anyOk = anyOk || toolResult.isOk();
                anyNewFact = anyNewFact || newKeyFact;
            }

            if (anyOk) {
                continuousFailures = 0;
                noNewKeyFactRounds = anyNewFact ? 0 : noNewKeyFactRounds + 1;
            } else {
                continuousFailures++;
            }
            if (continuousFailures >= MAX_CONTINUOUS_FAILURES) {
                finalReport = buildFailureReport(rounds);
                break;
            }
        }

        if (finalReport == null) {
            finalReport = buildMaxRoundReport(rounds);
        }

        String evidence = buildEvidenceLog(initialEvidence, rounds);
        String confidence = resolveConfidence(graph, rounds, finalReport);
        Long recordId = saveRecord(request, version, finalReport, confidence, evidence);

        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer(buildPlainAnswer(finalReport, evidence));
        result.setConclusion(finalReport);
        result.setConfidence(confidence);
        result.setEvidenceJson(evidence);
        return result;
    }

    /**
     * 角色与规则只在 system 消息里讲一次，后续轮次靠对话历史承载，不再每轮重复拼接。
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是只读 Bug 定位 Agent，只负责定位问题，不修改代码，不生成补丁。\n");
        prompt.append("必须基于证据判断；证据不足就调用工具继续查。本轮若需要多份互不依赖的证据（如同时读多个方法、查多张表），请一次返回多个 tool_calls 并行获取，减少往返；只有当下一步依赖上一步结果时才分轮。\n");
        prompt.append("初始证据里已预取入口与关键调用节点的源码快照，能直接判断时不要再读相同位置。\n");
        prompt.append("如果初始证据带了【异常堆栈定位】，那是报错的直接位置，必须优先核对栈顶业务代码那几行，再回溯调用链确认根因，不要绕开堆栈去猜。\n");
        prompt.append("当入口、调用链、SQL/数据源、返回模型、差异点这五类证据已经足够解释问题时，必须停止查证并调用 finish。\n");
        prompt.append("如果连续两轮没有新增关键事实，必须停止查证并收口。\n");
        prompt.append("同一文件、同一类、同一 SQL 已读过且没有新问题时，不要重复读取。\n");
        prompt.append("数据库只能执行只读 SQL。最终报告要让测试、实施能看懂，并保留开发可追溯证据。\n\n");
        prompt.append("【工具使用方式】\n");
        prompt.append("通过调用提供的工具（tool_calls）行动，不要自己手写 JSON，不要输出 Markdown。\n");
        prompt.append("调用工具前，可在回复正文用一两句话简述这步要查什么、为什么。\n");
        prompt.append("证据足够后调用 finish 工具，report 参数写最终报告；不要继续追查无关细节。\n");
        prompt.append("最终报告必须包含：通俗结论、问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度。\n");
        prompt.append("其中【通俗结论】放在最前面，用一句话讲清楚是什么问题，让运维、测试、实施都能看懂（例：xx 字段在数据库不存在、xx 字段存的值超长、必填字段没传值）。\n\n");
        prompt.append("【收口规则】\n");
        prompt.append("如果你已经能明确指出最可能的根因和最小修复点，就不要再查重复证据，直接 finish。\n");
        prompt.append("如果只能给出高概率判断，也要在最终报告里说明剩余风险，不要空转。\n");
        return prompt.toString();
    }

    private String buildInitialUserPrompt(String initialEvidence) {
        return "【初始证据】\n" + initialEvidence + "\n现在开始第 1 轮：基于已有证据决定下一步，继续查证或调用 finish 收敛。";
    }

    private String buildForceFinishInstruction(String forceFinishReason) {
        return "【强制收口要求】当前触发收口条件：" + forceFinishReason + "。本轮必须调用 finish，不要再调用查证工具。";
    }

    private String resolveForceFinishReason(CodeGraphQueryResult graph, List<Map<String, Object>> rounds, int noNewKeyFactRounds) {
        if (noNewKeyFactRounds >= MAX_NO_NEW_FACT_ROUNDS) {
            return "连续两轮没有新增关键事实";
        }
        if (gatheredCoreEvidence(graph, rounds)) {
            return "核心证据（入口、源码、数据库验证）已齐";
        }
        return null;
    }

    /**
     * 用结构化信号判断核心证据是否齐备，替代易误判的关键词嗅探：
     * 路由命中（图谱）+ 源码证据（预取快照或工具读取成功）+ 数据库验证（实际查过表/结构）。
     */
    private boolean gatheredCoreEvidence(CodeGraphQueryResult graph, List<Map<String, Object>> rounds) {
        boolean route = !graph.getRouteNodes().isEmpty();
        if (!route) {
            return false;
        }
        boolean code = hasSnapshotTarget(graph);
        boolean db = false;
        for (Map<String, Object> round : rounds) {
            if (!Boolean.TRUE.equals(round.get("toolOk"))) {
                continue;
            }
            String action = safe(round.get("action"));
            if ("get_code_detail".equals(action) || "trace_call_chain".equals(action) || "search_code".equals(action)) {
                code = true;
            }
            if ("query_database".equals(action) || "describe_tables".equals(action)) {
                db = true;
            }
        }
        return code && db;
    }

    private boolean isNewKeyFact(List<Map<String, Object>> rounds, AgentToolCall call, AgentToolResult toolResult) {
        // 判别信号（工具、参数、结果）放前面，思考过程放最后，避免长思考把签名挤出 300 字截断窗口
        String text = factSignatureText(safe(call.getAction()), safe(call.getArguments()),
                safe(toolResult.getSummary()), safe(toolResult.getEvidence()), safe(call.getThought()));
        if (text.contains("无源码定位信息") || text.contains("未找到") || text.contains("缺少")) {
            return false;
        }
        String normalized = normalizeFactSignature(text);
        for (Map<String, Object> round : rounds) {
            String previous = factSignatureText(safe(round.get("action")), safe(round.get("arguments")),
                    safe(round.get("toolSummary")), safe(round.get("toolEvidence")), safe(round.get("thought")));
            if (normalized.equals(normalizeFactSignature(previous))) {
                return false;
            }
        }
        return true;
    }

    private String factSignatureText(String action, String arguments, String summary, String evidence, String thought) {
        return (action + "\n" + arguments + "\n" + summary + "\n" + evidence + "\n" + thought).toLowerCase();
    }

    static String normalizeFactSignature(String text) {
        String normalized = (text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .replaceAll("nodeid=\\d+", "nodeid=#")
                .replaceAll("line=\\d+", "line=#")
                .replaceAll("id=\\d+", "id=#");
        if (normalized.length() > 300) {
            normalized = normalized.substring(0, 300);
        }
        return normalized;
    }

    private String buildForcedFinishReport(String reason, List<Map<String, Object>> rounds) {
        StringBuilder report = new StringBuilder();
        report.append("问题结论：Agent 已基于当前证据收口，原因判断以最强证据链为准。\n");
        report.append("收口原因：").append(reason).append("\n");
        report.append("证据链路：\n");
        for (Map<String, Object> round : rounds) {
            report.append("- 第").append(round.get("iteration")).append("轮：")
                    .append(safe(round.get("toolSummary"))).append("\n");
        }
        if (!rounds.isEmpty()) {
            Map<String, Object> lastRound = rounds.get(rounds.size() - 1);
            report.append("关键证据摘要：").append(extractEvidenceSummary(safe(lastRound.get("toolEvidence")))).append("\n");
        }
        report.append("建议处理人：后端开发\n");
        report.append("置信度：MEDIUM\n");
        report.append("建议处理：结合已有证据确认入口、调用链、SQL、返回模型是否一致，再做最小修复。\n");
        return report.toString();
    }

    /**
     * 解析本轮全部工具调用；无标准 tool_calls 时回退到单次 JSON/文本解析。
     */
    private List<AgentToolCall> parseToolCalls(AiToolCallResult aiResult) {
        List<AgentToolCall> calls = new ArrayList<AgentToolCall>();
        if (aiResult != null && aiResult.hasToolCall()) {
            for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
                if (isBlank(raw.getName())) {
                    continue;
                }
                AgentToolCall call = new AgentToolCall();
                call.setAction(raw.getName());
                call.setToolCallId(raw.getId());
                // 思考过程走 message content（多个调用共用本轮正文）
                call.setThought(aiResult.getContent());
                call.setArguments(parseArguments(raw.getArgumentsJson()));
                calls.add(call);
            }
            if (!calls.isEmpty()) {
                return calls;
            }
        }
        calls.add(parseToolCall(aiResult));
        return calls;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (isBlank(argumentsJson)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private AgentToolCall findFinish(List<AgentToolCall> calls) {
        for (AgentToolCall call : calls) {
            if ("finish".equals(call.getAction())) {
                return call;
            }
        }
        return null;
    }

    /**
     * 并行执行本轮的查证工具；单个工具时直接执行避免线程开销。
     */
    private List<AgentToolResult> executeAll(List<AgentToolCall> calls, AgentToolExecutor.AgentToolContext context) {
        if (calls.size() == 1) {
            List<AgentToolResult> single = new ArrayList<AgentToolResult>(1);
            single.add(safeExecute(calls.get(0), context));
            return single;
        }
        List<CompletableFuture<AgentToolResult>> futures = new ArrayList<CompletableFuture<AgentToolResult>>();
        for (AgentToolCall call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> safeExecute(call, context), toolFanoutPool));
        }
        List<AgentToolResult> results = new ArrayList<AgentToolResult>(calls.size());
        for (CompletableFuture<AgentToolResult> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    private AgentToolResult safeExecute(AgentToolCall call, AgentToolExecutor.AgentToolContext context) {
        try {
            return agentToolExecutor.execute(call, context);
        } catch (Exception exception) {
            return AgentToolResult.fail(safe(call.getAction()), "工具执行异常: " + exception.getMessage());
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 回填模型本轮的 assistant 消息（含 tool_calls）；拿不到原始消息时退化成普通文本消息。
     */
    private void appendAssistantMessage(List<Map<String, Object>> messages, AiToolCallResult aiResult) {
        Map<String, Object> assistant = aiResult.getAssistantMessage();
        if (assistant != null) {
            messages.add(assistant);
        } else {
            messages.add(message("assistant", safe(aiResult.getContent())));
        }
    }

    /**
     * 按 tool_call id 逐条回填工具结果。OpenAI 要求每个 tool_call 都要有配对的 tool 消息，
     * 缺失的兜底成"工具未执行"，避免下一轮请求被服务端拒绝。
     */
    private void appendToolMessages(List<Map<String, Object>> messages, AiToolCallResult aiResult,
                                    List<AgentToolCall> calls, List<AgentToolResult> results) {
        Map<String, String> contentById = new HashMap<String, String>();
        for (int i = 0; i < calls.size(); i++) {
            String id = calls.get(i).getToolCallId();
            if (id != null) {
                contentById.put(id, toolResultContent(results.get(i)));
            }
        }
        if (aiResult.getToolCalls().isEmpty()) {
            return;
        }
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", contentById.getOrDefault(id, "工具未执行"));
            messages.add(toolMessage);
        }
    }

    private String toolResultContent(AgentToolResult result) {
        StringBuilder content = new StringBuilder();
        content.append(result.isOk() ? "成功" : "失败").append(": ").append(safe(result.getSummary()));
        String evidence = safe(result.getEvidence());
        if (!evidence.isEmpty()) {
            content.append("\n").append(trim(evidence, appProperties.getAgent().getToolResultLimit()));
        }
        return content.toString();
    }

    /**
     * 独立一次 LLM 调用，让模型基于证据反驳自己的初步结论。
     * 用单独的会话（不污染主对话），结论站得住返回含 CONFIRM 的文本，有问题返回 REVISE。
     */
    private String runSelfCritique(String report, List<Map<String, Object>> rounds, String initialEvidence) {
        String evidence = trim(buildEvidenceLog(initialEvidence, rounds), appProperties.getAgent().getInitialEvidenceLimit());
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是严格的代码评审，专挑 Bug 定位结论的毛病。只基于下面的证据反驳这个初步结论：\n");
        prompt.append("1. 根因判断是否被证据直接支持，有没有臆测？\n");
        prompt.append("2. 证据链有没有断点（比如说字段不匹配，却没真的查过表结构或 SQL）？\n");
        prompt.append("3. 有没有比它更可能、却被忽略的原因？\n");
        prompt.append("结论站得住、证据自洽，第一行只回 CONFIRM。\n");
        prompt.append("有实质问题，第一行只回 REVISE，再用一句话说明还需要补查什么。不要复述结论，不要客套。\n\n");
        prompt.append("【初步结论】\n").append(safe(report)).append("\n\n");
        prompt.append("【已收集证据】\n").append(evidence);
        return aiClient.chat(prompt.toString());
    }

    /**
     * 判定复核结论是否要求修订。只有明确 REVISE 才打回，含糊或 CONFIRM 一律放行，避免无谓多查。
     */
    private boolean isReviseVerdict(String verdict) {
        if (isBlank(verdict)) {
            return false;
        }
        for (String line : verdict.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.toUpperCase().startsWith("REVISE");
        }
        return false;
    }

    private String reviseReason(String verdict) {
        String reason = safe(verdict).replaceFirst("(?i)^\\s*REVISE[:：]?", "").trim();
        return trim(reason.isEmpty() ? "证据链存在断点" : reason, 200);
    }

    /**
     * 闭合被拦下的本轮全部 tool_call，满足"每个 tool_call 必须有 tool 响应"的协议。
     * finish 给复核提示，同轮夹带的其他调用统一标暂缓。
     */
    private void appendFinishHold(List<Map<String, Object>> messages, AiToolCallResult aiResult, AgentToolCall finish) {
        String finishId = finish.getToolCallId();
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", id.equals(finishId)
                    ? "结论已收到，先做独立复核再决定是否采纳。"
                    : "本轮先复核初步结论，该调用暂缓。");
            messages.add(toolMessage);
        }
    }

    private Map<String, Object> recordCritique(int iteration, String report, String verdict) {
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("iteration", iteration);
        round.put("action", "self_critique");
        round.put("toolOk", true);
        round.put("toolSummary", "结论自检未通过，要求补证");
        round.put("toolEvidence", "初步结论:\n" + safe(report) + "\n复核意见:\n" + safe(verdict));
        return round;
    }

    private Map<String, Object> recordRound(int iteration, AiToolCallResult aiResult, AgentToolCall call, AgentToolResult toolResult) {
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("iteration", iteration);
        round.put("aiResponse", aiResult.hasToolCall() ? aiResult.getRawResponse() : aiResult.getContent());
        round.put("thought", call.getThought());
        round.put("action", call.getAction());
        round.put("arguments", call.getArguments());
        round.put("toolOk", toolResult.isOk());
        round.put("toolSummary", toolResult.getSummary());
        round.put("toolEvidence", toolResult.getEvidence());
        return round;
    }

    private AgentToolCall parseToolCall(AiToolCallResult aiResult) {
        if (aiResult != null && aiResult.hasToolCall()) {
            return parseStructuredToolCall(aiResult);
        }
        String aiResponse = aiResult == null ? null : aiResult.getContent();
        String json = extractJsonObject(aiResponse);
        if (json == null) {
            return finishCall("AI 未返回 JSON，按当前内容收敛：\n" + aiResponse);
        }
        try {
            Map<String, Object> values = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            AgentToolCall call = new AgentToolCall();
            call.setThought(stringValue(values.get("thought")));
            call.setAction(stringValue(values.get("action")));
            Object arguments = values.get("arguments");
            if (arguments instanceof Map) {
                call.setArguments((Map<String, Object>) arguments);
            }
            if (isBlank(call.getAction())) {
                return finishCall("AI JSON 缺少 action，原始内容：\n" + aiResponse);
            }
            return call;
        } catch (Exception exception) {
            return finishCall("AI JSON 解析失败: " + exception.getMessage() + "\n原始内容：\n" + aiResponse);
        }
    }

    private AgentToolCall parseStructuredToolCall(AiToolCallResult aiResult) {
        try {
            Map<String, Object> arguments = objectMapper.readValue(aiResult.getArgumentsJson(), new TypeReference<Map<String, Object>>() {});
            AgentToolCall call = new AgentToolCall();
            call.setAction(aiResult.getToolName());
            // 思考过程不再混进工具参数，从模型 message content 读取（拿不到就留空）
            call.setThought(aiResult.getContent());
            call.setArguments(arguments);
            return call;
        } catch (Exception exception) {
            return finishCall("AI tool_calls 参数解析失败: " + exception.getMessage() + "\n原始内容：\n" + aiResult.getRawResponse());
        }
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private AgentToolCall finishCall(String report) {
        AgentToolCall call = new AgentToolCall();
        call.setThought("模型输出无法继续结构化执行，直接收敛");
        call.setAction("finish");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("report", report);
        call.setArguments(arguments);
        return call;
    }

    /**
     * 解析请求里的日志文本，抠出线索；堆栈/traceId/时间为空时用日志里的补上。
     */
    private LogClues enrichFromLog(AnalysisRequest request) {
        LogClues clues = logEvidenceExtractor.extract(request.getLogText());
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

    private String buildInitialEvidence(AnalysisRequest request, ProjectVersion version,
                                        CodeGraphQueryResult graph, ProjectDatasource datasource,
                                        AgentToolExecutor.AgentToolContext toolContext, LogClues logClues) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目ID: ").append(request.getProjectId()).append("\n");
        builder.append("版本ID: ").append(version.getId()).append("\n");
        builder.append("API路径: ").append(request.getApiPath()).append("\n");
        appendIfPresent(builder, "用户描述", request.getUserDescription());
        appendIfPresent(builder, "请求参数", request.getRequestBody());
        appendIfPresent(builder, "响应结果", request.getResponseBody());
        appendIfPresent(builder, "异常堆栈", request.getStackTrace());
        String stackSnapshots = buildStackSnapshots(request.getStackTrace(), toolContext);
        if (!stackSnapshots.isEmpty()) {
            builder.append("异常堆栈定位(栈顶业务代码，报错的直接位置，优先从这里判断根因):\n").append(stackSnapshots).append("\n");
        }
        appendIfPresent(builder, "截图保存路径", request.getScreenshotPaths());
        appendIfPresent(builder, "Trace ID", request.getTraceId());
        appendIfPresent(builder, "请求时间", request.getRequestTime());
        builder.append("数据源: ").append(datasource == null ? "未配置" : datasource.getDbhubKey()).append("\n");
        builder.append("命中路由:\n").append(formatNodes(graph.getRouteNodes())).append("\n");
        builder.append("相关调用节点:\n").append(formatNodes(graph.getRelatedNodes())).append("\n");
        AppProperties.Agent agentConfig = appProperties.getAgent();
        String sqlText = graph.getSqlTexts().isEmpty() ? "无" : trim(String.join("\n", graph.getSqlTexts()), agentConfig.getSqlTextLimit());
        builder.append("SQL摘要:\n").append(sqlText).append("\n");
        builder.append("涉及表: ").append(graph.getTables()).append("\n");
        builder.append("源码快照(已自动预取入口与关键调用节点的代码，无需再次读取相同位置):\n")
                .append(buildSourceSnapshots(graph, toolContext)).append("\n");
        if (logClues != null && !logClues.getSqlLines().isEmpty()) {
            builder.append("日志中的SQL:\n").append(String.join("\n", logClues.getSqlLines())).append("\n");
        }
        if (logClues != null && !logClues.getErrorLines().isEmpty()) {
            builder.append("日志中的ERROR:\n").append(String.join("\n", logClues.getErrorLines())).append("\n");
        }
        // 总量兜底截断，防止大接口的初始证据撑爆上下文（多轮对话每次都会带上）
        return trim(builder.toString(), agentConfig.getInitialEvidenceLimit());
    }

    /**
     * 解析异常堆栈，把栈顶业务栈帧对应的源码片段预取进初始证据。
     * 有堆栈时这是最强定位信号——栈帧直接写明报错的类和行号。
     */
    private String buildStackSnapshots(String stackTrace, AgentToolExecutor.AgentToolContext toolContext) {
        List<StackFrame> frames = parseBusinessFrames(stackTrace);
        if (frames.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (StackFrame frame : frames) {
            String snippet = agentToolExecutor.readSourceAtClassLine(toolContext, frame.className, frame.lineNo, STACK_SNIPPET_LINES);
            builder.append("【").append(frame.className).append("#").append(frame.methodName)
                    .append(":").append(frame.lineNo).append("】\n")
                    .append(snippet).append("\n");
        }
        return builder.toString();
    }

    /**
     * 从堆栈文本里抽取业务栈帧，过滤框架/JDK 帧，按出现顺序取前 STACK_FRAME_LIMIT 个并去重。
     */
    private List<StackFrame> parseBusinessFrames(String stackTrace) {
        List<StackFrame> frames = new ArrayList<StackFrame>();
        if (isBlank(stackTrace)) {
            return frames;
        }
        // 有 "Caused by" 链时，根因在最后一段，优先从那解析，定位更贴近真实出错点
        int rootCause = stackTrace.lastIndexOf("Caused by:");
        String scope = rootCause >= 0 ? stackTrace.substring(rootCause) : stackTrace;
        Matcher matcher = STACK_FRAME_PATTERN.matcher(scope);
        Set<String> seen = new HashSet<String>();
        while (matcher.find() && frames.size() < STACK_FRAME_LIMIT) {
            String className = matcher.group(1);
            if (isFrameworkFrame(className)) {
                continue;
            }
            int lineNo;
            try {
                lineNo = Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (seen.add(className + ":" + lineNo)) {
                frames.add(new StackFrame(className, matcher.group(2), lineNo));
            }
        }
        return frames;
    }

    private boolean isFrameworkFrame(String className) {
        for (String prefix : STACK_SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条堆栈栈帧的关键信息。
     */
    private static final class StackFrame {
        private final String className;
        private final String methodName;
        private final int lineNo;

        private StackFrame(String className, String methodName, int lineNo) {
            this.className = className;
            this.methodName = methodName;
            this.lineNo = lineNo;
        }
    }

    /**
     * 预取路由入口与直接关联节点的源码片段，塞进初始证据，
     * 让多数分析在第 1-2 轮即可收敛，省掉来回 get_code_detail 的串行 LLM 往返。
     */
    private String buildSourceSnapshots(CodeGraphQueryResult graph, AgentToolExecutor.AgentToolContext toolContext) {
        List<CodeNode> targets = new ArrayList<CodeNode>();
        Set<String> seen = new HashSet<String>();
        collectSnapshotTargets(targets, seen, graph.getRouteNodes(), Integer.MAX_VALUE);
        collectSnapshotTargets(targets, seen, graph.getRelatedNodes(), SNAPSHOT_RELATED_LIMIT);
        if (targets.isEmpty()) {
            return "无（无可定位的源码节点）";
        }
        StringBuilder builder = new StringBuilder();
        for (CodeNode node : targets) {
            String snippet = agentToolExecutor.readSourceSnippet(node, toolContext, SNAPSHOT_SNIPPET_LINES);
            builder.append("【").append(node.getName()).append("】 ")
                    .append(node.getFilePath()).append(":").append(node.getLineNo()).append("\n")
                    .append(snippet).append("\n");
        }
        return builder.toString();
    }

    private void collectSnapshotTargets(List<CodeNode> targets, Set<String> seen, List<CodeNode> source, int limit) {
        if (source == null) {
            return;
        }
        int added = 0;
        for (CodeNode node : source) {
            if (added >= limit) {
                break;
            }
            if (node.getFilePath() == null || node.getLineNo() == null) {
                continue;
            }
            String key = node.getFilePath() + ":" + node.getLineNo();
            if (seen.add(key)) {
                targets.add(node);
                added++;
            }
        }
    }

    private boolean hasSnapshotTarget(CodeGraphQueryResult graph) {
        return nodeWithSource(graph.getRouteNodes()) || nodeWithSource(graph.getRelatedNodes());
    }

    private boolean nodeWithSource(List<CodeNode> nodes) {
        if (nodes == null) {
            return false;
        }
        for (CodeNode node : nodes) {
            if (node.getFilePath() != null && node.getLineNo() != null) {
                return true;
            }
        }
        return false;
    }

    private String formatNodes(List<CodeNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "无";
        }
        return nodes.stream()
                .map(node -> "nodeId=" + node.getId()
                        + ", type=" + node.getNodeType()
                        + ", name=" + node.getName()
                        + ", qualifiedName=" + node.getQualifiedName()
                        + ", file=" + node.getFilePath()
                        + ", line=" + node.getLineNo()
                        + ", metadata=" + node.getMetadataJson())
                .collect(Collectors.joining("\n"));
    }

    private String buildEvidenceLog(String initialEvidence, List<Map<String, Object>> rounds) {
        StringBuilder builder = new StringBuilder();
        builder.append("=== 初始证据 ===\n").append(initialEvidence).append("\n");
        for (Map<String, Object> round : rounds) {
            builder.append("=== Agent 第").append(round.get("iteration")).append("轮 ===\n");
            builder.append("思考: ").append(safe(round.get("thought"))).append("\n");
            builder.append("工具: ").append(safe(round.get("action"))).append("\n");
            builder.append("参数: ").append(safe(round.get("arguments"))).append("\n");
            builder.append("成功: ").append(safe(round.get("toolOk"))).append("\n");
            builder.append("摘要: ").append(safe(round.get("toolSummary"))).append("\n");
            builder.append("证据:\n").append(safe(round.get("toolEvidence"))).append("\n");
        }
        return builder.toString();
    }

    private String buildFailureReport(List<Map<String, Object>> rounds) {
        return "Agent 连续工具调用失败，已停止继续追查。\n"
                + "当前能确认的证据请查看证据页；最后一次失败为：\n"
                + (rounds.isEmpty() ? "无" : safe(rounds.get(rounds.size() - 1).get("toolSummary")));
    }

    private String buildMaxRoundReport(List<Map<String, Object>> rounds) {
        return "Agent 达到最大分析轮次，未主动 finish。\n"
                + "请查看证据页确认已查到的代码、SQL 和数据库结果。\n"
                + "最后一轮结果：" + (rounds.isEmpty() ? "无" : safe(rounds.get(rounds.size() - 1).get("toolSummary")));
    }

    private String buildPlainAnswer(String finalReport, String evidence) {
        String text = safe(finalReport) + "\n" + safe(evidence);
        // 先用确定性错误规则库出人话，命中即返回（准确率最高）
        String patternAnswer = errorPatternPlainAnswer(text);
        if (patternAnswer != null) {
            return patternAnswer;
        }
        // 其次取报告里模型写的"通俗结论"，没有再退回"问题结论"
        String plain = extractSectionFirstLine(finalReport, "通俗结论");
        if (isBlank(plain)) {
            plain = extractSectionFirstLine(finalReport, "问题结论");
        }
        if (!isBlank(plain) && !isGenericConclusion(plain)) {
            return "简单说：" + plain;
        }
        // 实在没有结构化结论，抓报告第一句有意义的话兜底，别甩通用废话
        String firstLine = firstMeaningfulLine(finalReport);
        if (!isBlank(firstLine)) {
            return "简单说：" + firstLine;
        }
        return "简单说：Agent 已完成分析，详细原因请看“分析报告”和“证据”标签。";
    }

    /**
     * 确定性错误模式库：命中常见数据库/运行时错误时，直接给运维、测试、实施能看懂的一句话。
     * 规则优先、LLM 兜底——规则覆盖的部分准确率拉满。没命中返回 null。
     */
    static String errorPatternPlainAnswer(String text) {
        String safeText = text == null ? "" : text;
        String columnAnswer = unknownColumnPlainAnswer(safeText);
        if (columnAnswer != null) {
            return columnAnswer;
        }
        Matcher matcher = DATA_TOO_LONG_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段存的值太长了，超过数据库该字段的长度限制，所以写入失败。让开发确认是否要截断或加长度校验，或让 DBA 确认字段长度要不要调大。";
        }
        matcher = DATA_TRUNCATED_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段存的值被截断了，通常是值超长或类型不匹配。让开发确认传入的值和字段类型/长度是否对得上。";
        }
        matcher = COLUMN_NULL_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：必填字段 " + matcher.group(1) + " 没有值（为空），但数据库要求它不能为空。让开发确认这个字段为什么没传值或没查到值。";
        }
        matcher = NO_DEFAULT_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：" + matcher.group(1) + " 字段没给值，而数据库又没设默认值，所以插入失败。让开发确认这个字段是否漏传。";
        }
        matcher = DUPLICATE_ENTRY_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：数据重复了，唯一键 " + matcher.group(2) + " 上已存在值 '" + matcher.group(1) + "'，不能再插入同样的。让开发确认是不是重复提交，或本该走更新而不是新增。";
        }
        matcher = TABLE_MISSING_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：SQL 用到的表 " + matcher.group(1) + " 在当前数据库里不存在。让实施/DBA 确认表是否漏建，或开发确认表名、连的库是否写错。";
        }
        matcher = INCORRECT_VALUE_PATTERN.matcher(safeText);
        if (matcher.find()) {
            return "简单说：存进数据库的值类型不对，字段期望 " + matcher.group(1) + " 类型，实际给的是 '" + matcher.group(2) + "'。让开发确认传入值的格式/类型。";
        }
        if (SQL_SYNTAX_PATTERN.matcher(safeText).find()) {
            return "简单说：SQL 语句语法有问题，数据库直接拒绝执行。常见于动态拼接漏了参数或写错关键字，让开发检查这条 SQL。";
        }
        if (safeText.contains("NullPointerException")) {
            return "简单说：代码里有个对象是空的（null）还被使用了，导致接口报错。让开发定位是哪个值没取到或没初始化。";
        }
        if (safeText.contains("NumberFormatException")) {
            return "简单说：程序想把一段文本转成数字，但内容不是合法数字，转换失败。让开发确认传入或存的值格式对不对。";
        }
        if (safeText.contains("IndexOutOfBoundsException") || safeText.contains("ArrayIndexOutOfBoundsException")) {
            return "简单说：代码访问列表或数组时下标越界了。让开发确认数据条数和取值逻辑。";
        }
        return null;
    }

    /**
     * 过滤强制收口那种"已基于证据收口"的通用结论，这类话当通俗答案没意义。
     */
    private boolean isGenericConclusion(String conclusion) {
        return conclusion.contains("已基于") || conclusion.contains("已完成分析") || conclusion.contains("达到最大");
    }

    private String firstMeaningfulLine(String report) {
        if (isBlank(report)) {
            return null;
        }
        for (String line : report.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 6) {
                return trim(trimmed, 120);
            }
        }
        return null;
    }

    /**
     * 当证据里包含 MySQL "Unknown column" 报错时，输出一句面向测试/实施的人话解释；否则返回 null。
     */
    static String unknownColumnPlainAnswer(String text) {
        String safeText = text == null ? "" : text;
        Matcher columnMatcher = UNKNOWN_COLUMN_PATTERN.matcher(safeText);
        if (!columnMatcher.find()) {
            return null;
        }
        String column = columnMatcher.group(1);
        Matcher tableMatcher = FROM_TABLE_PATTERN.matcher(safeText);
        String table = tableMatcher.find() ? tableMatcher.group(1).replace("`", "") : "相关表";
        return "简单说：问题出在数据库字段不匹配。SQL 查询用了 " + table + "." + column
                + " 字段，但当前数据库表里没有这个字段，所以接口报 500。让开发确认 SQL 是否写错，或让实施/DBA 确认表结构是否漏了字段。";
    }

    private String extractSectionFirstLine(String report, String sectionName) {
        if (isBlank(report)) {
            return null;
        }
        int start = report.indexOf(sectionName);
        if (start < 0) {
            return null;
        }
        int colon = report.indexOf('：', start);
        if (colon < 0) {
            colon = report.indexOf(':', start);
        }
        if (colon < 0) {
            return null;
        }
        int end = report.indexOf('\n', colon + 1);
        return report.substring(colon + 1, end < 0 ? report.length() : end).trim();
    }

    /**
     * 用结构化信号打分，避免依赖报告里的“证据”字样。
     * 信号：路由命中、命中源码、命中数据库证据、模型主动 finish。
     */
    private String resolveConfidence(CodeGraphQueryResult graph, List<Map<String, Object>> rounds, String finalReport) {
        if (graph.getRouteNodes().isEmpty()) {
            return "LOW";
        }
        Set<String> successfulTools = new HashSet<String>();
        boolean agentFinished = false;
        for (Map<String, Object> round : rounds) {
            if (!Boolean.TRUE.equals(round.get("toolOk"))) {
                continue;
            }
            String action = safe(round.get("action"));
            successfulTools.add(action);
            if ("finish".equals(action)) {
                agentFinished = true;
            }
        }
        int score = 0;
        // 路由命中已确认，给基础分
        score += 1;
        // 读到了源码证据：工具读取，或初始证据已预取源码快照
        if (successfulTools.contains("get_code_detail") || successfulTools.contains("trace_call_chain")
                || hasSnapshotTarget(graph)) {
            score += 1;
        }
        // 拿到了数据库证据（表结构或只读查询）
        if (successfulTools.contains("query_database") || successfulTools.contains("describe_tables")) {
            score += 1;
        }
        // 模型自己收敛出报告，说明证据自洽
        if (agentFinished) {
            score += 1;
        }
        if (score >= 3) {
            return "HIGH";
        }
        if (score >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private Long saveRecord(AnalysisRequest request, ProjectVersion version, String conclusion, String confidence, String evidence) {
        jdbcTemplate.update(
                "insert into analysis_record(project_id, version_id, api_path, user_description, request_body, response_body, stack_trace, screenshot_paths, trace_id, request_time, conclusion, confidence, evidence_json, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                request.getProjectId(), version.getId(), request.getApiPath(),
                request.getUserDescription(), request.getRequestBody(), request.getResponseBody(), request.getStackTrace(),
                request.getScreenshotPaths(), request.getTraceId(), request.getRequestTime(), conclusion, confidence, evidence);
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
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

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append(label).append(": ").append(value).append("\n");
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String extractEvidenceSummary(String evidence) {
        if (isBlank(evidence)) {
            return "无";
        }
        String normalized = evidence.replace("\r", "");
        int lineBreak = normalized.indexOf("\n");
        if (lineBreak < 0) {
            return trim(normalized, 300);
        }
        return trim(normalized.substring(0, lineBreak), 300);
    }
    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

