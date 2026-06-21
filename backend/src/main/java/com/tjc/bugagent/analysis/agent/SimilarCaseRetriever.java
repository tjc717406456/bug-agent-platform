package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 从同项目历史里捞最相似的已确认案例，喂回实时分析当方向参考。
 * 只取标注过或自动验证确认过的记录当候选，用 api_path / 描述词重叠 / 异常类这些硬信号打分排序。
 */
@Component
public class SimilarCaseRetriever {

    // 候选集只圈"靠得住"的：人工标注过或机器验证 CONFIRMED 的，复用评测飞轮那套筛选条件
    private static final String CANDIDATE_SQL =
            "select id, api_path, conclusion, confidence, actual_root_cause, "
                    + "coalesce(nullif(expect_keywords, ''), auto_verify_keywords) as keywords, "
                    + "user_description, stack_trace from analysis_record "
                    + "where project_id = ? "
                    + "and ((feedback_verdict is not null and expect_keywords is not null and expect_keywords <> '' and expect_keywords <> '[]') "
                    + "  or (auto_verify = 'CONFIRMED' and auto_verify_keywords is not null and auto_verify_keywords <> '')) "
                    + "order by id desc limit 200";

    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([A-Za-z_][\\w$.]*Exception)");
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5]+");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public SimilarCaseRetriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AppProperties appProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 检索 topK 条相似确认案例。开关关闭、无候选或都达不到最低分时返回空列表，主流程据此不注入。
     */
    public List<SimilarCase> retrieve(AnalysisRequest request, ProjectVersion version) {
        AppProperties.Agent agent = appProperties.getAgent();
        if (!agent.isEnableSimilarCase()) {
            return new ArrayList<SimilarCase>();
        }
        String currentPath = safe(request.getApiPath());
        Set<String> currentWords = tokenize(request.getUserDescription());
        String currentException = topException(request.getStackTrace());

        List<SimilarCase> scored = jdbcTemplate.query(CANDIDATE_SQL, (rs, rowNum) -> {
            String candidatePath = safe(rs.getString("api_path"));
            String candidateDesc = safe(rs.getString("user_description"));
            // 排除与本次完全雷同的自身回放，避免把刚跑的记录当"历史"塞回去
            if (candidatePath.equals(currentPath) && candidateDesc.equals(safe(request.getUserDescription()))) {
                return null;
            }
            int score = score(currentPath, currentWords, currentException, candidatePath, candidateDesc, safe(rs.getString("stack_trace")));
            String rootCause = resolveRootCause(rs.getString("actual_root_cause"), rs.getString("conclusion"));
            return new SimilarCase(candidatePath, rootCause, formatKeywords(rs.getString("keywords")),
                    safe(rs.getString("confidence")), score);
        }, request.getProjectId());

        List<SimilarCase> result = new ArrayList<SimilarCase>();
        for (SimilarCase candidate : scored) {
            if (candidate != null && candidate.getScore() >= agent.getSimilarCaseMinScore()) {
                result.add(candidate);
            }
        }
        result.sort(Comparator.comparingInt(SimilarCase::getScore).reversed());
        int topK = Math.max(0, agent.getSimilarCaseTopK());
        return result.size() > topK ? new ArrayList<SimilarCase>(result.subList(0, topK)) : result;
    }

    /**
     * 打分：api_path 是强信号，描述词重叠和异常类同源做辅助微调。
     */
    private int score(String currentPath, Set<String> currentWords, String currentException,
                      String candidatePath, String candidateDesc, String candidateStack) {
        int score = 0;
        if (!currentPath.isEmpty() && currentPath.equals(candidatePath)) {
            score += 100;
        } else if (pathRelated(currentPath, candidatePath)) {
            score += 40;
        }
        if (!currentWords.isEmpty()) {
            Set<String> candidateWords = tokenize(candidateDesc);
            candidateWords.retainAll(currentWords);
            score += candidateWords.size() * 5;
        }
        if (!isBlank(currentException) && currentException.equals(topException(candidateStack))) {
            score += 30;
        }
        return score;
    }

    private boolean pathRelated(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    private Set<String> tokenize(String text) {
        Set<String> words = new HashSet<String>();
        if (isBlank(text)) {
            return words;
        }
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String word = matcher.group();
            // 单字符词噪声大，过滤掉
            if (word.length() > 1) {
                words.add(word);
            }
        }
        return words;
    }

    private String topException(String stackTrace) {
        if (isBlank(stackTrace)) {
            return null;
        }
        Matcher matcher = EXCEPTION_PATTERN.matcher(stackTrace);
        if (!matcher.find()) {
            return null;
        }
        String exception = matcher.group(1);
        int lastDot = exception.lastIndexOf('.');
        return lastDot >= 0 ? exception.substring(lastDot + 1) : exception;
    }

    /** 真实根因优先取人工确认的，没有就退回结论首个非空行。 */
    private String resolveRootCause(String actualRootCause, String conclusion) {
        if (!isBlank(actualRootCause)) {
            return trim(actualRootCause.trim(), 200);
        }
        for (String line : safe(conclusion).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trim(trimmed, 200);
            }
        }
        return "";
    }

    /** 关键词存的是 JSON 数组，转成逗号串展示；解析不了就用原文。 */
    private String formatKeywords(String keywordsJson) {
        if (isBlank(keywordsJson)) {
            return "";
        }
        try {
            List<String> keywords = objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {});
            return String.join(",", keywords);
        } catch (Exception exception) {
            return keywordsJson.trim();
        }
    }
}
