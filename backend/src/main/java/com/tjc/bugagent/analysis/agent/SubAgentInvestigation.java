package com.tjc.bugagent.analysis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多子 Agent 调查汇总，供主 Agent 注入证据和累计成本。
 */
public class SubAgentInvestigation {
    private static final int MAX_RESULT_LENGTH = 8000;
    private final List<SubAgentResult> results;

    public SubAgentInvestigation(List<SubAgentResult> results) {
        if (results == null || results.isEmpty()) {
            this.results = Collections.emptyList();
            return;
        }
        List<SubAgentResult> effective = new ArrayList<SubAgentResult>();
        for (SubAgentResult result : results) {
            if (result != null && result.hasConfirmedEvidence()) {
                effective.add(result);
            }
        }
        this.results = Collections.unmodifiableList(effective);
    }

    public List<SubAgentResult> getResults() { return results; }

    public int totalTokens() {
        int total = 0;
        for (SubAgentResult result : results) {
            total += result.getTotalTokens();
        }
        return total;
    }

    public int totalIterations() {
        int total = 0;
        for (SubAgentResult result : results) {
            total += result.getIterations();
        }
        return total;
    }

    /** 返回子 Agent 的结构化工具轮次，供主 Agent 判重和最终证据页复用。 */
    public List<Map<String, Object>> rounds() {
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        for (SubAgentResult result : results) {
            rounds.addAll(result.getRounds());
        }
        return rounds;
    }

    /** 合并子 Agent 已成功执行的工具缓存，避免主 Agent 重复读取同一份证据。 */
    public Map<String, AgentToolResult> cachedToolResults() {
        Map<String, AgentToolResult> cache = new LinkedHashMap<String, AgentToolResult>();
        for (SubAgentResult result : results) {
            cache.putAll(result.getCachedToolResults());
        }
        return cache;
    }

    public String evidencePrompt() {
        StringBuilder prompt = new StringBuilder("【多 Agent 独立调查证据】\n");
        for (SubAgentResult result : results) {
            prompt.append("\n【").append(result.getRole()).append("】\n")
                    .append(result.getEvidence() == null ? "未产出有效证据"
                            : AgentTextUtils.trim(result.getEvidence(), MAX_RESULT_LENGTH)).append("\n");
        }
        prompt.append("\n以上工具结果已完成并进入主任务缓存。禁止重复相同工具和参数，也不要自行扩展调查方向。"
                + "只有交接包明确列出的关键证据缺口允许补查；若没有明确缺口，第一轮直接调用 finish 收口。\n");
        return prompt.toString();
    }
}
