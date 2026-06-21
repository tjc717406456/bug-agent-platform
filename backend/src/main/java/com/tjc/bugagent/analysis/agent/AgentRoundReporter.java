package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 把每轮查证过程记成结构化 round，并据此生成证据日志和各类兜底报告文本。
 * round 里的 key（iteration/action/toolOk/toolSummary/toolEvidence 等）是收敛判定和报告的共用契约。
 */
@Component
public class AgentRoundReporter {

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
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("iteration", iteration);
        round.put("action", "self_critique");
        round.put("toolOk", true);
        round.put("toolSummary", "结论自检未通过，要求补证");
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
        StringBuilder report = new StringBuilder();
        report.append("【通俗结论】Agent 已达最大分析轮次仍未自行收口，下面是已查到的证据汇总，供人工判断。\n\n");
        report.append("【已收集证据】\n");
        if (rounds.isEmpty()) {
            report.append("无\n");
        } else {
            for (Map<String, Object> round : rounds) {
                report.append("- 第").append(safe(round.get("iteration"))).append("轮 · ")
                        .append(actionName(safe(round.get("action")))).append("：")
                        .append(trim(safe(round.get("toolSummary")), 80)).append("\n");
                String summary = extractEvidenceSummary(safe(round.get("toolEvidence")));
                if (!"无".equals(summary)) {
                    report.append("    ").append(trim(summary, 200)).append("\n");
                }
            }
        }
        report.append("\n【根因类型】未形成自洽结论，需结合上述证据人工研判。\n");
        report.append("【建议处理人】后端开发\n");
        report.append("【置信度】LOW\n");
        return report.toString();
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
