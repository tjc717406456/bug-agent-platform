package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 把每轮查证过程记成结构化 round，并据此生成证据日志和各类兜底报告文本。
 * round 里的 key（iteration/action/toolOk/toolSummary/toolEvidence 等）是收敛判定和报告的共用契约。
 */
@Component
public class AgentRoundReporter {
    private static final Pattern REPORT_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.#-]{3,}");

    public Map<String, Object> recordRound(int iteration, AiToolCallResult aiResult, AgentToolCall call, AgentToolResult toolResult) {
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

    public Map<String, Object> recordCritique(int iteration, String report, String verdict) {
        return recordCritique(iteration, report, verdict, "结论自检未通过，要求补证");
    }

    /** 记录自检结果，既支持打回补证，也支持仅收窄最终报告措辞。 */
    public Map<String, Object> recordCritique(int iteration, String report, String verdict, String summary) {
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("iteration", iteration);
        round.put("action", "self_critique");
        round.put("toolOk", true);
        round.put("toolSummary", summary);
        round.put("toolEvidence", "初步结论:\n" + safe(report) + "\n复核意见:\n" + safe(verdict));
        return round;
    }

    public String buildEvidenceLog(String initialEvidence, List<Map<String, Object>> rounds) {
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

    /**
     * 为独立自检挑选证据：保留精简初始信息，优先放入与结论标识匹配的直接源码，再补最近日志和数据证据。
     */
    public String buildCritiqueEvidence(String initialEvidence, List<Map<String, Object>> rounds,
                                        String report, int maxLength) {
        int limit = Math.max(2000, maxLength);
        StringBuilder builder = new StringBuilder();
        builder.append("=== 初始证据摘要 ===\n")
                .append(AgentTextUtils.trimHeadTail(initialEvidence, Math.min(3000, limit / 3))).append("\n");
        Set<String> identifiers = reportIdentifiers(report);
        Set<String> appended = new LinkedHashSet<String>();
        appendMatchingRounds(builder, rounds, identifiers, appended, limit, true);
        appendMatchingRounds(builder, rounds, identifiers, appended, limit, false);
        appendRecentRounds(builder, rounds, appended, limit);
        return builder.toString();
    }

    private void appendMatchingRounds(StringBuilder builder, List<Map<String, Object>> rounds,
                                      Set<String> identifiers, Set<String> appended, int limit,
                                      boolean directSourceOnly) {
        if (rounds == null) {
            return;
        }
        for (Map<String, Object> round : rounds) {
            String action = safe(round.get("action"));
            boolean directSource = "get_code_detail".equals(action) || "read_source".equals(action);
            if (directSourceOnly != directSource || !Boolean.TRUE.equals(round.get("toolOk"))) {
                continue;
            }
            String evidence = safe(round.get("toolEvidence"));
            if (!matchesAnyIdentifier(evidence, identifiers)) {
                continue;
            }
            appendCritiqueRound(builder, round, appended, limit);
        }
    }

    private void appendRecentRounds(StringBuilder builder, List<Map<String, Object>> rounds,
                                    Set<String> appended, int limit) {
        if (rounds == null) {
            return;
        }
        for (int index = rounds.size() - 1; index >= 0 && appended.size() < 6; index--) {
            Map<String, Object> round = rounds.get(index);
            if (Boolean.TRUE.equals(round.get("toolOk"))) {
                appendCritiqueRound(builder, round, appended, limit);
            }
        }
    }

    private void appendCritiqueRound(StringBuilder builder, Map<String, Object> round,
                                     Set<String> appended, int limit) {
        String key = safe(round.get("action")) + "|" + safe(round.get("arguments"));
        if (!appended.add(key) || builder.length() >= limit) {
            return;
        }
        int remaining = limit - builder.length();
        String block = "=== 关键证据 · 第" + safe(round.get("iteration")) + "轮 ===\n"
                + "工具: " + safe(round.get("action")) + "\n参数: " + safe(round.get("arguments"))
                + "\n摘要: " + safe(round.get("toolSummary")) + "\n证据:\n"
                + AgentTextUtils.trimHeadTail(safe(round.get("toolEvidence")), Math.min(3500, remaining)) + "\n";
        builder.append(AgentTextUtils.trimHeadTail(block, remaining));
    }

    private Set<String> reportIdentifiers(String report) {
        Set<String> identifiers = new LinkedHashSet<String>();
        Matcher matcher = REPORT_IDENTIFIER.matcher(safe(report));
        while (matcher.find()) {
            String identifier = matcher.group().toLowerCase();
            if (!"resultcode".equals(identifier) && !"resultmsg".equals(identifier)
                    && !"responsemodel".equals(identifier)) {
                identifiers.add(identifier);
            }
        }
        return identifiers;
    }

    private boolean matchesAnyIdentifier(String evidence, Set<String> identifiers) {
        if (identifiers.isEmpty()) {
            return true;
        }
        String normalized = safe(evidence).toLowerCase();
        for (String identifier : identifiers) {
            if (normalized.contains(identifier)) {
                return true;
            }
        }
        return false;
    }

    public String buildForcedFinishReport(String reason, List<Map<String, Object>> rounds) {
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

    public String buildFailureReport(List<Map<String, Object>> rounds) {
        return "Agent 连续工具调用失败，已停止继续追查。\n"
                + "当前能确认的证据请查看证据页；最后一次失败为：\n"
                + (rounds.isEmpty() ? "无" : safe(rounds.get(rounds.size() - 1).get("toolSummary")));
    }

    /**
     * 本地兜底报告：把各轮查到的证据汇总出来，避免只甩一句"达到最大轮次"。
     */
    public String buildMaxRoundReport(List<Map<String, Object>> rounds) {
        return ensureAnalysisReportFormat("Agent 已达最大分析轮次仍未形成自洽结论，需要结合已收集证据人工研判。", rounds);
    }

    /** 校验 Bug 定位报告是否包含统一的八段结构。 */
    public boolean isCompleteAnalysisReport(String report) {
        if (isBlank(report)) {
            return false;
        }
        return hasCoreAnalysisSections(report)
                && report.contains("【给开发 AI 的修复提示】");
    }

    private boolean hasCoreAnalysisSections(String report) {
        return report.contains("【通俗结论】")
                && report.contains("【问题结论】")
                && report.contains("【证据链路】")
                && report.contains("【关键代码/SQL/数据证据】")
                && report.contains("【根因类型】")
                && report.contains("【建议处理人】")
                && report.contains("【置信度】");
    }

    /**
     * 把不符合协议的模型输出装入统一报告结构，防止最大轮次收尾用一段短文覆盖完整报告。
     */
    public String ensureAnalysisReportFormat(String candidate, List<Map<String, Object>> rounds) {
        if (isCompleteAnalysisReport(candidate)) {
            return candidate;
        }
        if (!isBlank(candidate) && hasCoreAnalysisSections(candidate)) {
            StringBuilder completed = new StringBuilder(safe(candidate).trim());
            appendDeveloperAiRepairHint(completed, developerRepairIssue(candidate), rounds);
            return completed.toString();
        }
        String conclusion = isBlank(candidate)
                ? "Agent 已达最大分析轮次仍未形成自洽结论，需要结合已收集证据人工研判。"
                : safe(candidate).trim();
        StringBuilder report = new StringBuilder();
        report.append("【通俗结论】").append(firstMeaningfulLine(conclusion)).append("\n\n");
        report.append("【问题结论】\n").append(conclusion).append("\n\n");
        report.append("【证据链路】\n");
        if (rounds.isEmpty()) {
            report.append("无\n");
        } else {
            for (Map<String, Object> round : rounds) {
                report.append("- 第").append(safe(round.get("iteration"))).append("轮 · ")
                        .append(actionName(safe(round.get("action")))).append("：")
                        .append(trim(safe(round.get("toolSummary")), 80)).append("\n");
            }
        }
        report.append("\n【关键代码/SQL/数据证据】\n");
        appendEvidenceSummaries(report, rounds);
        report.append("\n【根因类型】待人工确认（最终模型输出未按结构化协议标注）\n");
        report.append("【建议处理人】后端开发\n");
        report.append("【置信度】LOW\n");
        appendDeveloperAiRepairHint(report, firstMeaningfulLine(conclusion), rounds);
        return report.toString();
    }

    private void appendDeveloperAiRepairHint(StringBuilder report, String issue, List<Map<String, Object>> rounds) {
        report.append("\n【给开发 AI 的修复提示】\n");
        report.append("问题：请根据本报告修复：").append(trim(issue, 180)).append("\n");
        report.append("已确认：当前报告已记录可追溯的源码、日志、SQL 或数据证据；实施时只采信其中明确确认的事实，")
                .append("未确认内容继续保留为风险，不要擅自补成确定结论。\n");
        report.append("修改目标：先读取当前分支相关 Controller、Service、Mapper、响应模型及调用方，")
                .append("围绕已定位根因做最小修改，复用现有字段、错误码和公共能力，不改变无关流程；")
                .append("若修复方式涉及接口契约或产品行为，先确认兼容方案。\n");
        report.append("验证：复现原异常，检查修复后的主流程、失败分支和关键边界场景，确认返回语义与现有调用方兼容。");
        if (!rounds.isEmpty()) {
            report.append("优先复核证据页中最近几轮的源码和运行日志。");
        }
        report.append("这只是修复提示，不是已验证补丁，请读取当前代码后再实施。\n");
    }

    private String developerRepairIssue(String report) {
        String firstLine = firstMeaningfulLine(report);
        return trim(firstLine.replaceFirst("^【通俗结论】\\s*", ""), 180);
    }

    private void appendEvidenceSummaries(StringBuilder report, List<Map<String, Object>> rounds) {
        int appended = 0;
        for (int index = rounds.size() - 1; index >= 0 && appended < 5; index--) {
            Map<String, Object> round = rounds.get(index);
            String summary = extractEvidenceSummary(safe(round.get("toolEvidence")));
            if (!"无".equals(summary)) {
                report.append("- 第").append(safe(round.get("iteration"))).append("轮：")
                        .append(trim(summary, 300)).append("\n");
                appended++;
            }
        }
        if (appended == 0) {
            report.append("无\n");
        }
    }

    private String firstMeaningfulLine(String text) {
        for (String line : safe(text).split("\\r?\\n")) {
            String value = line.trim();
            if (!value.isEmpty()) {
                return trim(value, 240);
            }
        }
        return "当前证据不足，需人工确认。";
    }

    /**
     * 工具名转成进度展示用的中文动作。
     */
    public String actionName(String action) {
        if ("search_code".equals(action)) {
            return "搜索代码";
        }
        if ("get_code_detail".equals(action)) {
            return "读取源码";
        }
        if ("read_source".equals(action)) {
            return "按行读取源码";
        }
        if ("trace_call_chain".equals(action)) {
            return "追踪调用链";
        }
        if ("search_sql".equals(action)) {
            return "搜索SQL";
        }
        if ("describe_tables".equals(action)) {
            return "查询表结构";
        }
        if ("query_database".equals(action)) {
            return "执行只读SQL";
        }
        return safe(action);
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
}
