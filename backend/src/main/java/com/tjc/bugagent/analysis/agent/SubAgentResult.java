package com.tjc.bugagent.analysis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 调查结果，只承载压缩证据，不直接作为最终分析报告。
 */
public class SubAgentResult {
    private static final int MAX_HANDOFF_LENGTH = 4000;
    private final String role;
    private final String evidence;
    private final int totalTokens;
    private final int iterations;
    private final AgentStopReason stopReason;
    private final List<Map<String, Object>> rounds;
    private final Map<String, AgentToolResult> cachedToolResults;

    public SubAgentResult(String role, AgentRunResult result, List<Map<String, Object>> rounds,
                          Map<String, AgentToolResult> cachedToolResults) {
        this.role = role;
        this.evidence = result == null ? null : AgentTextUtils.trim(result.getFinalContent(), MAX_HANDOFF_LENGTH);
        this.totalTokens = result == null ? 0 : result.getTotalTokens();
        this.iterations = result == null ? 0 : result.getIterations();
        this.stopReason = result == null ? AgentStopReason.INTERNAL_ERROR : result.getStopReason();
        this.rounds = immutableRounds(rounds);
        this.cachedToolResults = cachedToolResults == null ? Collections.<String, AgentToolResult>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, AgentToolResult>(cachedToolResults));
    }

    private List<Map<String, Object>> immutableRounds(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> round : source) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(round)));
        }
        return Collections.unmodifiableList(copy);
    }

    public String getRole() { return role; }
    public String getEvidence() { return evidence; }
    public int getTotalTokens() { return totalTokens; }
    public int getIterations() { return iterations; }
    public AgentStopReason getStopReason() { return stopReason; }
    public List<Map<String, Object>> getRounds() { return rounds; }
    public Map<String, AgentToolResult> getCachedToolResults() { return cachedToolResults; }

    /**
     * 只有成功工具产生了可引用证据时才算有效交接，取消、模型失败和纯说明文本不进入主 Agent。
     */
    public boolean hasConfirmedEvidence() {
        if (stopReason == AgentStopReason.CANCELLED || stopReason == AgentStopReason.INTERRUPTED
                || stopReason == AgentStopReason.MODEL_ERROR || stopReason == AgentStopReason.INTERNAL_ERROR) {
            return false;
        }
        for (Map<String, Object> round : rounds) {
            String toolEvidence = AgentTextUtils.safe(round.get("toolEvidence"));
            if (Boolean.TRUE.equals(round.get("toolOk"))
                    && !"finish".equals(AgentTextUtils.safe(round.get("action")))
                    && hasConcreteEvidence(toolEvidence)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConcreteEvidence(String evidence) {
        if (AgentTextUtils.isBlank(evidence)) {
            return false;
        }
        String normalized = evidence.trim();
        return !"无".equals(normalized) && !normalized.contains("无直接命中证据");
    }
}
