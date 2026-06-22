package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trimHeadTail;

/**
 * Agent 收敛与可信度判定：用结构化信号决定何时强制收口、初步结论要不要复核、最终置信度评级。
 * 替代易误判的关键词嗅探，全部基于路由命中 / 源码证据 / 数据库证据这些硬信号。
 */
@Component
public class AgentConvergenceJudge {

    private static final int MAX_NO_NEW_FACT_ROUNDS = 2;

    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final AgentRoundReporter roundReporter;

    public AgentConvergenceJudge(AiClient aiClient, AppProperties appProperties, AgentRoundReporter roundReporter) {
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.roundReporter = roundReporter;
    }

    public String resolveForceFinishReason(CodeGraphQueryResult graph, List<Map<String, Object>> rounds, int noNewKeyFactRounds) {
        if (noNewKeyFactRounds >= MAX_NO_NEW_FACT_ROUNDS) {
            return "连续两轮没有新增关键事实";
        }
        if (gatheredCoreEvidence(graph, rounds)) {
            return "核心证据（入口、源码、数据库验证）已齐";
        }
        return null;
    }

    /**
     * 用结构化信号判断核心证据是否齐备：路由命中（图谱）+ 源码证据（预取快照或工具读取成功）
     * + 数据库验证（实际查过表/结构）。
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

    public boolean isNewKeyFact(List<Map<String, Object>> rounds, AgentToolCall call, AgentToolResult toolResult) {
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

    /**
     * 用结构化信号打分：路由命中、命中源码、命中数据库证据、模型主动 finish。
     */
    public String resolveConfidence(CodeGraphQueryResult graph, List<Map<String, Object>> rounds, String finalReport) {
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

    /**
     * 独立一次 LLM 调用，让模型基于证据反驳自己的初步结论。
     * 用单独的会话（不污染主对话），结论站得住返回含 CONFIRM 的文本，有问题返回 REVISE。
     */
    public String runSelfCritique(String report, List<Map<String, Object>> rounds, String initialEvidence) {
        // 自检证据保头留尾：头是入口/堆栈/路由定位，尾是末轮根因查证，超限时挖掉中间探索轮
        String evidence = trimHeadTail(roundReporter.buildEvidenceLog(initialEvidence, rounds), appProperties.getAgent().getInitialEvidenceLimit());
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
    public boolean isReviseVerdict(String verdict) {
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

    public String reviseReason(String verdict) {
        String reason = safe(verdict).replaceFirst("(?i)^\\s*REVISE[:：]?", "").trim();
        return trim(reason.isEmpty() ? "证据链存在断点" : reason, 200);
    }
}
