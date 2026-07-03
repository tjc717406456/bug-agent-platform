package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;

/**
 * 多假设侦察兵：单次 LLM 调用，基于初始证据列出候选根因 + 置信分。
 * 解析失败或模型不配合返回空列表，主流程据此自动退回单链，永不让侦察拖垮分析。
 */
@Component
public class HypothesisScout {
    private static final Logger log = LoggerFactory.getLogger(HypothesisScout.class);

    private final AiClient aiClient;
    private final AgentPromptBuilder promptBuilder;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public HypothesisScout(AiClient aiClient, AgentPromptBuilder promptBuilder,
                           AppProperties appProperties, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.promptBuilder = promptBuilder;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 产出候选根因，按置信分从高到低排序。侦察这笔 LLM 开销计入 tokenSink，账面别漏。
     */
    public List<Hypothesis> scout(String initialEvidence, AtomicInteger tokenSink) {
        int maxCount = Math.max(2, appProperties.getAgent().getHypothesisMaxBranches());
        String reply;
        try {
            AiToolCallResult result = aiClient.chatUtilityResult(promptBuilder.buildHypothesisScoutPrompt(initialEvidence, maxCount));
            tokenSink.addAndGet(result.getTotalTokens());
            if (result.isFailed()) {
                log.warn("多假设侦察调用失败，退回单链: {}", result.getContent());
                return Collections.emptyList();
            }
            reply = result.getContent();
        } catch (Exception exception) {
            log.warn("多假设侦察调用失败，退回单链: {}", exception.getMessage());
            return Collections.emptyList();
        }
        List<Hypothesis> hypotheses = parse(reply);
        hypotheses.sort(Comparator.comparingInt(Hypothesis::getScore).reversed());
        return hypotheses;
    }

    private List<Hypothesis> parse(String reply) {
        List<Hypothesis> result = new ArrayList<Hypothesis>();
        String json = extractJsonArray(reply);
        if (json == null) {
            return result;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> item : items) {
                String cause = safe(item.get("cause")).trim();
                if (isBlank(cause)) {
                    continue;
                }
                result.add(new Hypothesis(cause, toScore(item.get("score"))));
            }
        } catch (Exception exception) {
            log.warn("多假设侦察结果解析失败，退回单链: {}", exception.getMessage());
        }
        return result;
    }

    private int toScore(Object value) {
        if (value instanceof Number) {
            return clamp(((Number) value).intValue());
        }
        try {
            return clamp((int) Math.round(Double.parseDouble(safe(value).trim())));
        } catch (Exception exception) {
            return 0;
        }
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String extractJsonArray(String text) {
        if (isBlank(text)) {
            return null;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
