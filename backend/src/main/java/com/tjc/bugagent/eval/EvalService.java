package com.tjc.bugagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.AgentAnalysisService;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.AnalysisResult;
import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 跑评估用例集，给分析准确率打分。改完 prompt/逻辑跑一遍，看命中率有没有退步。
 */
@Service
public class EvalService {
    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    // 置信度高低排序，用于判断实际置信度是否达到期望
    private static final List<String> CONFIDENCE_RANK = Arrays.asList("LOW", "MEDIUM", "HIGH");

    private final AgentAnalysisService agentAnalysisService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;

    public EvalService(AgentAnalysisService agentAnalysisService, ObjectMapper objectMapper,
                       AppProperties appProperties, JdbcTemplate jdbcTemplate) {
        this.agentAnalysisService = agentAnalysisService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 跑一批用例。传入为空时从配置的用例文件加载。
     */
    public EvalSummary run(List<EvalCase> cases) {
        List<EvalCase> target = (cases == null || cases.isEmpty()) ? loadFromFile() : cases;
        return runCases(target);
    }

    /**
     * 评估飞轮：把人工标注过的真实 bug 当回归用例重跑，看改完逻辑后还命不命中人工要求的关键词。
     */
    public EvalSummary runFromFeedback() {
        return runCases(loadFromFeedback());
    }

    private EvalSummary runCases(List<EvalCase> target) {
        List<EvalCaseResult> results = new ArrayList<EvalCaseResult>();
        for (EvalCase evalCase : target) {
            results.add(evaluate(evalCase));
        }
        return summarize(results);
    }

    private EvalCaseResult evaluate(EvalCase evalCase) {
        EvalCaseResult result = new EvalCaseResult();
        result.setName(evalCase.getName());
        result.setExpectConfidence(evalCase.getExpectConfidence());
        long start = System.currentTimeMillis();
        try {
            AnalysisResult analysis = agentAnalysisService.analyze(toRequest(evalCase));
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setConfidence(analysis.getConfidence());
            result.setPlainAnswer(analysis.getPlainAnswer());
            scoreKeywords(evalCase, analysis, result);
            result.setConfidenceOk(confidenceReached(evalCase.getExpectConfidence(), analysis.getConfidence()));
            result.setPassed(result.getMatchedKeywords() == result.getTotalKeywords() && result.isConfidenceOk());
        } catch (Exception exception) {
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setErrorMessage(exception.getMessage());
            result.setPassed(false);
            result.setConfidenceOk(false);
            log.warn("eval case failed: {}", evalCase.getName(), exception);
        }
        return result;
    }

    /**
     * 关键词命中打分：期望关键词全部出现在通俗答案或结论里才算定位准。
     */
    private void scoreKeywords(EvalCase evalCase, AnalysisResult analysis, EvalCaseResult result) {
        List<String> keywords = evalCase.getExpectKeywords();
        if (keywords == null || keywords.isEmpty()) {
            result.setTotalKeywords(0);
            result.setMatchedKeywords(0);
            result.setMatchRatio(1.0);
            result.setMissedKeywords(new ArrayList<String>());
            return;
        }
        String text = (safe(analysis.getPlainAnswer()) + "\n" + safe(analysis.getConclusion())).toLowerCase();
        List<String> missed = new ArrayList<String>();
        int matched = 0;
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase())) {
                matched++;
            } else {
                missed.add(keyword);
            }
        }
        result.setTotalKeywords(keywords.size());
        result.setMatchedKeywords(matched);
        result.setMatchRatio((double) matched / keywords.size());
        result.setMissedKeywords(missed);
    }

    private boolean confidenceReached(String expect, String actual) {
        if (isBlank(expect)) {
            return true;
        }
        int expectRank = CONFIDENCE_RANK.indexOf(expect.trim().toUpperCase());
        int actualRank = CONFIDENCE_RANK.indexOf(safe(actual).trim().toUpperCase());
        return actualRank >= expectRank;
    }

    private EvalSummary summarize(List<EvalCaseResult> results) {
        EvalSummary summary = new EvalSummary();
        summary.setResults(results);
        summary.setTotal(results.size());
        if (results.isEmpty()) {
            return summary;
        }
        int passed = 0;
        int confidenceHit = 0;
        double ratioSum = 0;
        long elapsed = 0;
        for (EvalCaseResult result : results) {
            if (result.isPassed()) {
                passed++;
            }
            if (result.isConfidenceOk()) {
                confidenceHit++;
            }
            ratioSum += result.getMatchRatio();
            elapsed += result.getElapsedMs();
        }
        summary.setPassed(passed);
        summary.setAccuracy((double) passed / results.size());
        summary.setAvgMatchRatio(ratioSum / results.size());
        summary.setConfidenceHitRate((double) confidenceHit / results.size());
        summary.setTotalElapsedMs(elapsed);
        return summary;
    }

    private List<EvalCase> loadFromFile() {
        String path = appProperties.getEval().getCasesPath();
        if (isBlank(path)) {
            return new ArrayList<EvalCase>();
        }
        Path casesPath = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(casesPath)) {
            log.warn("eval cases file not found: {}", casesPath);
            return new ArrayList<EvalCase>();
        }
        try {
            byte[] bytes = Files.readAllBytes(casesPath);
            return objectMapper.readValue(bytes, new TypeReference<List<EvalCase>>() {});
        } catch (Exception exception) {
            log.error("load eval cases failed: {}", casesPath, exception);
            return new ArrayList<EvalCase>();
        }
    }

    /**
     * 从带反馈的分析记录构造用例：输入沿用当时记录的入参，期望关键词用人工标注的。
     */
    private List<EvalCase> loadFromFeedback() {
        return jdbcTemplate.query(
                "select id, project_id, version_id, api_path, user_description, request_body, response_body, "
                        + "stack_trace, trace_id, request_time, expect_keywords from analysis_record "
                        + "where feedback_verdict is not null and expect_keywords is not null and expect_keywords <> '' and expect_keywords <> '[]'",
                (rs, rowNum) -> {
                    EvalCase evalCase = new EvalCase();
                    evalCase.setName("record#" + rs.getLong("id") + " " + safe(rs.getString("api_path")));
                    evalCase.setProjectId(rs.getLong("project_id"));
                    long versionId = rs.getLong("version_id");
                    evalCase.setVersionId(rs.wasNull() ? null : versionId);
                    evalCase.setApiPath(rs.getString("api_path"));
                    evalCase.setUserDescription(rs.getString("user_description"));
                    evalCase.setRequestBody(rs.getString("request_body"));
                    evalCase.setResponseBody(rs.getString("response_body"));
                    evalCase.setStackTrace(rs.getString("stack_trace"));
                    evalCase.setTraceId(rs.getString("trace_id"));
                    evalCase.setRequestTime(rs.getString("request_time"));
                    evalCase.setExpectKeywords(parseKeywords(rs.getString("expect_keywords")));
                    return evalCase;
                });
    }

    private List<String> parseKeywords(String json) {
        if (isBlank(json)) {
            return new ArrayList<String>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception exception) {
            return new ArrayList<String>();
        }
    }

    private AnalysisRequest toRequest(EvalCase evalCase) {
        AnalysisRequest request = new AnalysisRequest();
        request.setProjectId(evalCase.getProjectId());
        request.setVersionId(evalCase.getVersionId());
        request.setApiPath(evalCase.getApiPath());
        request.setUserDescription(evalCase.getUserDescription());
        request.setRequestBody(evalCase.getRequestBody());
        request.setResponseBody(evalCase.getResponseBody());
        request.setStackTrace(evalCase.getStackTrace());
        request.setTraceId(evalCase.getTraceId());
        request.setRequestTime(evalCase.getRequestTime());
        return request;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
