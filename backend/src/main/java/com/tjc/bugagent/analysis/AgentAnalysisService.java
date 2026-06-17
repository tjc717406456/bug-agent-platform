package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 只读 Bug 定位 Agent，围绕代码图谱、SQL 和数据库证据做多轮分析。
 */
@Service
public class AgentAnalysisService {
    private static final int MAX_ITERATIONS = 6;
    private static final int MAX_CONTINUOUS_FAILURES = 2;
    private static final int MAX_NO_NEW_FACT_ROUNDS = 2;
    private static final Pattern UNKNOWN_COLUMN_PATTERN = Pattern.compile("Unknown column '([^']+)'");
    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([`\\w.]+)");

    private final ProjectService projectService;
    private final CodeGraphQueryService codeGraphQueryService;
    private final AgentToolExecutor agentToolExecutor;
    private final AiClient aiClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentAnalysisService(ProjectService projectService,
                                CodeGraphQueryService codeGraphQueryService,
                                AgentToolExecutor agentToolExecutor,
                                AiClient aiClient,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.agentToolExecutor = agentToolExecutor;
        this.aiClient = aiClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        String initialEvidence = buildInitialEvidence(request, version, graph, datasource);
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        String finalReport = null;
        int continuousFailures = 0;
        int noNewKeyFactRounds = 0;

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            String forceFinishReason = resolveForceFinishReason(initialEvidence, rounds, noNewKeyFactRounds);
            String prompt = buildAgentPrompt(initialEvidence, rounds, iteration, forceFinishReason);
            AiToolCallResult aiResult = aiClient.chatWithTools(prompt, agentToolExecutor.toolSchemas());
            String aiResponse = aiResult.hasToolCall() ? aiResult.getRawResponse() : aiResult.getContent();
            AgentToolCall call = parseToolCall(aiResult);
            if (!isBlank(forceFinishReason) && !"finish".equals(call.getAction())) {
                call = finishCall(buildForcedFinishReport(forceFinishReason, rounds));
            }

            Map<String, Object> round = new LinkedHashMap<String, Object>();
            round.put("iteration", iteration);
            round.put("aiResponse", aiResponse);
            round.put("thought", call.getThought());
            round.put("action", call.getAction());
            round.put("arguments", call.getArguments());

            AgentToolResult toolResult = agentToolExecutor.execute(call, toolContext);
            boolean newKeyFact = isNewKeyFact(rounds, call, toolResult);
            round.put("toolOk", toolResult.isOk());
            round.put("toolSummary", toolResult.getSummary());
            round.put("toolEvidence", toolResult.getEvidence());
            rounds.add(round);

            if ("finish".equals(call.getAction())) {
                finalReport = toolResult.getEvidence();
                break;
            }
            if (toolResult.isOk()) {
                continuousFailures = 0;
                noNewKeyFactRounds = newKeyFact ? 0 : noNewKeyFactRounds + 1;
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

    private String buildAgentPrompt(String initialEvidence, List<Map<String, Object>> rounds, int iteration, String forceFinishReason) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是只读 Bug 定位 Agent，只负责定位问题，不修改代码，不生成补丁。\n");
        prompt.append("必须基于证据判断；证据不足就调用工具继续查，每轮只能调用一个工具。\n");
        prompt.append("当入口、调用链、SQL/数据源、返回模型、差异点这五类证据已经足够解释问题时，必须停止查证并调用 finish。\n");
        prompt.append("如果连续两轮没有新增关键事实，必须停止查证并收口。\n");
        prompt.append("同一文件、同一类、同一 SQL 已读过且没有新问题时，不要重复读取。\n");
        prompt.append("数据库只能执行只读 SQL。最终报告要让测试、实施能看懂，并保留开发可追溯证据。\n\n");
        prompt.append("【工具使用方式】\n");
        prompt.append("通过调用提供的工具（tool_calls）行动，不要自己手写 JSON，不要输出 Markdown。\n");
        prompt.append("调用工具前，可在回复正文用一两句话简述这步要查什么、为什么。\n");
        prompt.append("证据足够后调用 finish 工具，report 参数写最终报告；不要继续追查无关细节。\n");
        prompt.append("最终报告必须包含：问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度。\n\n");
        prompt.append("【收口规则】\n");
        prompt.append("如果你已经能明确指出最可能的根因和最小修复点，就不要再查重复证据，直接 finish。\n");
        prompt.append("如果只能给出高概率判断，也要在最终报告里说明剩余风险，不要空转。\n\n");
        if (!isBlank(forceFinishReason)) {
            prompt.append("【强制收口要求】\n");
            prompt.append("当前触发收口条件：").append(forceFinishReason).append("。本轮必须调用 finish，不要再调用查证工具。\n\n");
        }
        prompt.append("【初始证据】\n").append(initialEvidence).append("\n");
        if (!rounds.isEmpty()) {
            prompt.append("【已执行轮次】\n");
            for (Map<String, Object> round : rounds) {
                prompt.append("第").append(round.get("iteration")).append("轮\n");
                prompt.append("思考: ").append(trim(safe(round.get("thought")), 120)).append("\n");
                prompt.append("工具: ").append(safe(round.get("action"))).append("\n");
                prompt.append("结果: ").append(trim(safe(round.get("toolSummary")), 120)).append("\n");
                prompt.append("证据摘要: ").append(extractEvidenceSummary(safe(round.get("toolEvidence")))).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("现在是第").append(iteration).append("轮，请基于已有证据决定下一步：继续查证，或调用 finish 收敛。\n");
        return prompt.toString();
    }

    private String resolveForceFinishReason(String initialEvidence, List<Map<String, Object>> rounds, int noNewKeyFactRounds) {
        if (noNewKeyFactRounds >= MAX_NO_NEW_FACT_ROUNDS) {
            return "连续两轮没有新增关键事实";
        }
        if (hasEnoughEvidence(initialEvidence, rounds)) {
            return "证据已经足够形成结论";
        }
        return null;
    }

    private boolean hasEnoughEvidence(String initialEvidence, List<Map<String, Object>> rounds) {
        String evidence = buildEvidenceLog(initialEvidence, rounds).toLowerCase();
        boolean hasRoute = evidence.contains("命中路由") || evidence.contains("route");
        boolean hasCallChain = evidence.contains("相关调用节点") || evidence.contains("调用链") || evidence.contains("service");
        boolean hasSql = evidence.contains("sql") || evidence.contains("数据库") || evidence.contains("select ");
        boolean hasModel = evidence.contains("返回对象") || evidence.contains("dto") || evidence.contains("vo") || evidence.contains("entity");
        boolean hasDiff = evidence.contains("不一致") || evidence.contains("缺失") || evidence.contains("没返回") || evidence.contains("未返回") || evidence.contains("映射");
        return hasRoute && hasCallChain && hasSql && hasModel && hasDiff;
    }

    private boolean isNewKeyFact(List<Map<String, Object>> rounds, AgentToolCall call, AgentToolResult toolResult) {
        String text = (safe(call.getThought()) + "\n" + safe(call.getAction()) + "\n" + safe(call.getArguments()) + "\n" + safe(toolResult.getSummary()) + "\n" + safe(toolResult.getEvidence())).toLowerCase();
        if (text.contains("无源码定位信息") || text.contains("未找到") || text.contains("缺少")) {
            return false;
        }
        String normalized = normalizeFactSignature(text);
        for (Map<String, Object> round : rounds) {
            String previous = (safe(round.get("thought")) + "\n" + safe(round.get("action")) + "\n" + safe(round.get("arguments")) + "\n" + safe(round.get("toolSummary")) + "\n" + safe(round.get("toolEvidence"))).toLowerCase();
            if (normalized.equals(normalizeFactSignature(previous))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeFactSignature(String text) {
        String normalized = safe(text)
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

    private String buildInitialEvidence(AnalysisRequest request, ProjectVersion version,
                                        CodeGraphQueryResult graph, ProjectDatasource datasource) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目ID: ").append(request.getProjectId()).append("\n");
        builder.append("版本ID: ").append(version.getId()).append("\n");
        builder.append("API路径: ").append(request.getApiPath()).append("\n");
        appendIfPresent(builder, "用户描述", request.getUserDescription());
        appendIfPresent(builder, "请求参数", request.getRequestBody());
        appendIfPresent(builder, "响应结果", request.getResponseBody());
        appendIfPresent(builder, "异常堆栈", request.getStackTrace());
        appendIfPresent(builder, "截图保存路径", request.getScreenshotPaths());
        appendIfPresent(builder, "Trace ID", request.getTraceId());
        appendIfPresent(builder, "请求时间", request.getRequestTime());
        builder.append("数据源: ").append(datasource == null ? "未配置" : datasource.getDbhubKey()).append("\n");
        builder.append("命中路由:\n").append(formatNodes(graph.getRouteNodes())).append("\n");
        builder.append("相关调用节点:\n").append(formatNodes(graph.getRelatedNodes())).append("\n");
        builder.append("SQL摘要:\n").append(graph.getSqlTexts().isEmpty() ? "无" : String.join("\n", graph.getSqlTexts())).append("\n");
        builder.append("涉及表: ").append(graph.getTables()).append("\n");
        return builder.toString();
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
        Matcher columnMatcher = UNKNOWN_COLUMN_PATTERN.matcher(text);
        if (columnMatcher.find()) {
            String column = columnMatcher.group(1);
            Matcher tableMatcher = FROM_TABLE_PATTERN.matcher(text);
            String table = tableMatcher.find() ? tableMatcher.group(1).replace("`", "") : "相关表";
            return "简单说：问题出在数据库字段不匹配。SQL 查询用了 " + table + "." + column
                    + " 字段，但当前数据库表里没有这个字段，所以接口报 500。让开发确认 SQL 是否写错，或让实施/DBA 确认表结构是否漏了字段。";
        }
        String conclusion = extractSectionFirstLine(finalReport, "问题结论");
        if (!isBlank(conclusion)) {
            return "简单说：" + conclusion;
        }
        return "简单说：Agent 已完成分析，详细原因请看“分析报告”和“证据”标签。";
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
        // 读到了源码证据（调用链或源码片段）
        if (successfulTools.contains("get_code_detail") || successfulTools.contains("trace_call_chain")) {
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

