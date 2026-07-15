package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * Agent 收敛与可信度判定：用结构化信号决定何时强制收口、初步结论要不要复核、最终置信度评级。
 * 替代易误判的关键词嗅探，全部基于路由命中 / 源码证据 / 数据库证据这些硬信号。
 */
@Component
public class AgentConvergenceJudge {

    // 连续多少轮无新增关键事实即强制收口。编排层日志也引用，故包可见
    static final int MAX_NO_NEW_FACT_ROUNDS = 2;
    private static final Pattern GAP_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.#-]{2,}");

    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final AgentRoundReporter roundReporter;

    public AgentConvergenceJudge(AiClient aiClient, AppProperties appProperties, AgentRoundReporter roundReporter) {
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.roundReporter = roundReporter;
    }

    /** 硬收口只认空转：连续无新事实才强制。证据类型齐备不再是强制条件——碰过三类证据不等于查透了。 */
    public String resolveForceFinishReason(int noNewKeyFactRounds) {
        if (noNewKeyFactRounds >= MAX_NO_NEW_FACT_ROUNDS) {
            return "连续两轮没有新增关键事实";
        }
        return null;
    }

    /** 核心证据齐备的软信号：编排层只提醒一次模型考虑收口，不剥夺继续深挖的权利。 */
    public boolean coreEvidenceGathered(CodeGraphQueryResult graph, List<Map<String, Object>> rounds) {
        return gatheredCoreEvidence(graph, rounds);
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
            if ("get_code_detail".equals(action) || "read_source".equals(action)
                    || "trace_call_chain".equals(action) || "search_code".equals(action)) {
                code = true;
            }
            if ("query_database".equals(action) || "describe_tables".equals(action)) {
                db = true;
            }
        }
        return code && db;
    }

    public boolean isNewKeyFact(List<Map<String, Object>> rounds, AgentToolCall call, AgentToolResult toolResult) {
        // 失败/查无结果用结构化状态判，不再嗅探正文关键词——证据里恰好含"未找到"仨字不该被误杀
        if (!toolResult.isOk()) {
            return false;
        }
        String normalized = normalizeFactSignature(factSignatureText(safe(call.getAction()), safe(call.getArguments()),
                safe(toolResult.getSummary()), safe(toolResult.getEvidence())));
        for (Map<String, Object> round : rounds) {
            String previous = factSignatureText(safe(round.get("action")), safe(round.get("arguments")),
                    safe(round.get("toolSummary")), safe(round.get("toolEvidence")));
            if (normalized.equals(normalizeFactSignature(previous))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断本轮工具是否直接推进当前关键证据缺口，避免无关新文本反复重置空转计数。
     */
    public boolean advancesEvidenceGap(String gap, AgentToolCall call, AgentToolResult toolResult) {
        if (isBlank(gap)) {
            return false;
        }
        if (call == null || toolResult == null || !toolResult.isOk() || isBlank(toolResult.getEvidence())) {
            return false;
        }
        String normalizedGap = gap.toLowerCase();
        String action = safe(call.getAction());
        String evidence = (safe(toolResult.getSummary()) + " " + safe(toolResult.getEvidence())).toLowerCase();
        if (containsAny(normalizedGap, "源码", "代码", "方法", "分支", "返回", "响应", "字段", "赋值")
                && containsAny(action, "get_code_detail", "read_source", "grep_source", "search_code",
                "trace_call_chain", "find_callers")) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "日志", "时间", "回执", "ack", "超时", "req", "trace")
                && "search_log".equals(action)) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "数据库", "业务数据", "记录", "配置值", "查询结果")
                && "query_database".equals(action)) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "sql", "mapper")
                && ("search_sql".equals(action) || "grep_source".equals(action)
                || "read_source".equals(action) || "get_code_detail".equals(action))) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "表结构", "列类型", "字段类型", "表字段")
                && ("describe_tables".equals(action) || "search_sql".equals(action))) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "调用链", "入口", "controller", "调用方")
                && ("trace_call_chain".equals(action) || "find_callers".equals(action)
                || "get_code_detail".equals(action) || "read_source".equals(action))) {
            return matchesGapIdentifier(normalizedGap, evidence);
        }
        for (String term : normalizedGap.split("[^\\p{L}\\p{N}_]+")) {
            if (term.length() >= 4 && evidence.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断工具结果是否已经闭合当前证据缺口。搜索类工具只负责定位，源码缺口必须由直接读取结果证明。
     */
    public boolean resolvesEvidenceGap(String gap, AgentToolCall call, AgentToolResult toolResult) {
        if (isBlank(gap) || call == null || toolResult == null || !toolResult.isOk()
                || isBlank(toolResult.getEvidence())) {
            return false;
        }
        String normalizedGap = gap.toLowerCase();
        String action = safe(call.getAction());
        String evidence = (safe(toolResult.getSummary()) + " " + safe(toolResult.getEvidence())).toLowerCase();
        if (containsAny(normalizedGap, "源码", "代码", "方法", "分支", "返回", "响应", "字段", "赋值")) {
            if (!"get_code_detail".equals(action) && !"read_source".equals(action)) {
                return false;
            }
            return matchesGapIdentifier(normalizedGap, evidence) && matchesRequiredField(normalizedGap, evidence)
                    && matchesBranchRelationship(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "日志", "时间", "回执", "ack", "超时", "req", "trace")) {
            return "search_log".equals(action) && matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "数据库", "业务数据", "记录", "配置值", "查询结果")) {
            return "query_database".equals(action) && matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "表结构", "列类型", "字段类型", "表字段")) {
            return "describe_tables".equals(action) && matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "sql", "mapper")) {
            return ("search_sql".equals(action) || "get_code_detail".equals(action)
                    || "read_source".equals(action)) && matchesGapIdentifier(normalizedGap, evidence);
        }
        if (containsAny(normalizedGap, "调用链", "入口", "controller", "调用方")) {
            return ("trace_call_chain".equals(action) || "find_callers".equals(action)
                    || "get_code_detail".equals(action) || "read_source".equals(action))
                    && matchesGapIdentifier(normalizedGap, evidence);
        }
        return matchesGapIdentifier(normalizedGap, evidence);
    }

    /** 搜索工具首次命中目标标识时只算定位成功，不等同于证据已经闭合。 */
    public boolean locatesEvidenceGap(String gap, AgentToolCall call, AgentToolResult toolResult) {
        if (isBlank(gap) || call == null || toolResult == null || !toolResult.isOk()) {
            return false;
        }
        String action = safe(call.getAction());
        if (!"search_code".equals(action) && !"grep_source".equals(action)
                && !"search_sql".equals(action)) {
            return false;
        }
        String evidence = (safe(toolResult.getSummary()) + " " + safe(toolResult.getEvidence())).toLowerCase();
        return matchesGapIdentifier(gap.toLowerCase(), evidence);
    }

    private boolean matchesRequiredField(String gap, String evidence) {
        if (!containsAny(gap, "字段", "设置", "携带", "放入", "赋值")) {
            return true;
        }
        Matcher matcher = GAP_IDENTIFIER_PATTERN.matcher(gap);
        String candidate = null;
        while (matcher.find()) {
            String value = matcher.group().toLowerCase();
            if (!containsAny(value, "controller", "service", "impl", "responsemodel", "result", "false", "true")) {
                candidate = value;
            }
        }
        return candidate == null || evidence.contains(candidate);
    }

    private boolean matchesBranchRelationship(String gap, String evidence) {
        if (!containsAny(gap, "分支", "result=false", "失败", "成功")) {
            return true;
        }
        boolean hasBranch = containsAny(evidence, "if (!result", "if(!result", "result=false",
                "result == false", "result==false", "失败");
        boolean hasReturn = containsAny(evidence, "return", "返回");
        return hasBranch && hasReturn;
    }

    /** 有类名、方法名、字段名或请求标识时，工具结果必须实际包含其中一项。 */
    private boolean matchesGapIdentifier(String gap, String evidence) {
        Matcher matcher = GAP_IDENTIFIER_PATTERN.matcher(gap);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (evidence.contains(matcher.group().toLowerCase())) {
                return true;
            }
        }
        return !found;
    }

    /** 判断文本是否包含任一证据类别关键词。 */
    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    // 判重只看确定性的工具侧字段（工具、参数、摘要、证据）。thought 是模型自由文本，
    // 措辞每轮都变，纳入只会破坏判重；存证展示仍保留在 evidence log，不影响这里
    private String factSignatureText(String action, String arguments, String summary, String evidence) {
        return (action + "\n" + arguments + "\n" + summary + "\n" + evidence).toLowerCase();
    }

    static String normalizeFactSignature(String text) {
        // 不截断：全文 equals 成本可忽略，截 300 字反而把"前缀相同、后半不同"的新证据误判成重复
        return (text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .replaceAll("nodeid=\\d+", "nodeid=#")
                .replaceAll("line=\\d+", "line=#")
                .replaceAll("id=\\d+", "id=#");
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
        if (successfulTools.contains("get_code_detail") || successfulTools.contains("read_source")
                || successfulTools.contains("trace_call_chain")
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
     * 独立一次 LLM 调用复核初步结论，不污染主对话。
     * CONFIRM 直接收口，REFRAME 只修正报告边界，REVISE 才继续补查；复核开销计入 tokenSink。
     */
    public String runSelfCritique(String report, List<Map<String, Object>> rounds, String initialEvidence, AtomicInteger tokenSink) {
        String evidence = roundReporter.buildCritiqueEvidence(initialEvidence, rounds, report,
                appProperties.getAgent().getInitialEvidenceLimit());
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 Bug 分析结论复核员。判断初步结论是否足以回答用户问题，不追求证明所有下游细节。\n");
        prompt.append("复核规则：\n");
        prompt.append("1. 只检查核心结论是否有日志、源码、SQL 或数据证据支持。\n");
        prompt.append("2. 区分已确认的应用侧原因与尚不能确认的下游具体原因；未确认部分已写成限制或剩余风险即可。\n");
        prompt.append("3. 不得要求当前证据源无法提供的材料，例如用户未提供的生产数据库、硬件端日志、网关日志或现场网络状态。\n");
        prompt.append("4. 不得为了百分之百确定性继续补查；合理排除会改变处理方向的主要替代原因即可。\n");
        prompt.append("5. 表述过满但核心结论成立，只要求收窄措辞，不要求继续调查。\n");
        prompt.append("6. 只有缺失证据会实质改变根因判断、处理方向或用户答案时，才要求继续补查，且证据必须能从现有工具和数据源获得。\n");
        prompt.append("7. 若业务源码已明确显示失败分支提前返回，且目标字段只在成功分支显式追加，即可确认业务代码未在失败响应中设置该字段；除非已有证据显示框架会自动补字段，不得继续要求公共响应模型内部实现或实际 HTTP 响应日志。\n");
        prompt.append("输出规则：\n");
        prompt.append("- 核心结论成立且证据足够，第一行只回 CONFIRM。\n");
        prompt.append("- 核心结论成立但措辞需要收窄，第一行只回 REFRAME，第二行用一句话说明如何修改。\n");
        prompt.append("- 缺少会改变根因判断的关键证据，第一行只回 REVISE，第二行只说明一个最关键且可获取的证据缺口。\n");
        prompt.append("不要复述报告，不要提出与用户问题无关的深层验证要求。\n\n");
        prompt.append("【初步结论】\n").append(safe(report)).append("\n\n");
        prompt.append("【已收集证据】\n").append(evidence);
        AiToolCallResult result = aiClient.chatWithTools(prompt.toString(), null);
        tokenSink.addAndGet(result.getTotalTokens());
        return result.getContent();
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

    /** 判断复核是否只要求收窄报告措辞，不再继续调用工具补证。 */
    public boolean isReframeVerdict(String verdict) {
        return firstVerdictLineStartsWith(verdict, "REFRAME");
    }

    private boolean firstVerdictLineStartsWith(String verdict, String prefix) {
        if (isBlank(verdict)) {
            return false;
        }
        for (String line : verdict.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.toUpperCase().startsWith(prefix);
            }
        }
        return false;
    }

    public String reviseReason(String verdict) {
        String reason = safe(verdict).replaceFirst("(?i)^\\s*REVISE[:：]?", "").trim();
        return trim(reason.isEmpty() ? "证据链存在断点" : reason, 200);
    }

    /** 提取 REFRAME 后的措辞修正要求，供最终报告生成阶段使用。 */
    public String reframeReason(String verdict) {
        String reason = safe(verdict).replaceFirst("(?i)^\\s*REFRAME[:：]?", "").trim();
        return trim(reason.isEmpty() ? "收窄未被证据直接支持的表述，并明确剩余风险" : reason, 200);
    }
}
