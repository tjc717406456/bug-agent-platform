package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.analysis.AnalysisRequest;
import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([A-Za-z_][\\w$.]*Exception)");
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5]+");

    private static final Logger log = LoggerFactory.getLogger(SimilarCaseRetriever.class);

    private final AnalysisRecordMapper analysisRecordMapper;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final AiClient aiClient;

    public SimilarCaseRetriever(AnalysisRecordMapper analysisRecordMapper, ObjectMapper objectMapper, AppProperties appProperties, AiClient aiClient) {
        this.analysisRecordMapper = analysisRecordMapper;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.aiClient = aiClient;
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

        // 候选集只圈"靠得住"的：人工标注过或机器验证 CONFIRMED 的（筛选条件见 Mapper XML）
        List<Map<String, Object>> rows = analysisRecordMapper.selectCandidates(request.getProjectId());
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Map<String, Object> row : rows) {
            String candidatePath = safe(str(row, "apiPath"));
            String candidateDesc = safe(str(row, "userDescription"));
            // 排除与本次完全雷同的自身回放，避免把刚跑的记录当"历史"塞回去
            if (candidatePath.equals(currentPath) && candidateDesc.equals(safe(request.getUserDescription()))) {
                continue;
            }
            String candidateStack = str(row, "stackTrace");
            Candidate candidate = new Candidate();
            candidate.id = ((Number) row.get("id")).longValue();
            candidate.apiPath = candidatePath;
            candidate.rootCause = resolveRootCause(str(row, "actualRootCause"), str(row, "conclusion"));
            candidate.keywords = formatKeywords(str(row, "keywords"));
            candidate.confidence = safe(str(row, "confidence"));
            candidate.lexical = score(currentPath, currentWords, currentException, candidatePath, candidateDesc, safe(candidateStack));
            candidate.embeddingJson = str(row, "embedding");
            candidate.embedText = caseText(candidatePath, candidateDesc, candidate.rootCause, topException(candidateStack));
            candidates.add(candidate);
        }

        // 词法分打底，开了语义召回再叠加余弦相似度，"意思像但说法不同"的旧案例也能排上号
        applySemantic(candidates, caseText(currentPath, safe(request.getUserDescription()), "", currentException));

        List<SimilarCase> result = new ArrayList<SimilarCase>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.finalScore >= agent.getSimilarCaseMinScore()) {
                result.add(new SimilarCase(candidate.apiPath, candidate.rootCause, candidate.keywords, candidate.confidence, candidate.finalScore));
            }
        }
        result.sort(Comparator.comparingInt(SimilarCase::getScore).reversed());
        int topK = Math.max(0, agent.getSimilarCaseTopK());
        return result.size() > topK ? new ArrayList<SimilarCase>(result.subList(0, topK)) : result;
    }

    /**
     * 给候选叠加语义分：最终分先取词法分，未配 embedding 模型或当前文本 embed 失败就此返回（纯词法、零回归）。
     * 候选缺缓存向量时在预算内现算并回写 analysis_record.embedding，语料随多次分析逐步补全。
     */
    private void applySemantic(List<Candidate> candidates, String currentText) {
        for (Candidate candidate : candidates) {
            if (candidate != null) {
                candidate.finalScore = candidate.lexical;
            }
        }
        AppProperties.Ai ai = appProperties.getAi();
        // embed 返回 null 即未启用 EMBEDDING 配置或调用失败，退回纯词法（finalScore 已置为词法分）
        float[] current = aiClient.embed(currentText);
        if (current == null) {
            return;
        }
        int budget = ai.getEmbeddingBackfillBudget();
        int weight = ai.getEmbeddingWeight();
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            float[] vector = parseStoredEmbedding(candidate.embeddingJson);
            if (vector == null && budget > 0) {
                vector = aiClient.embed(candidate.embedText);
                if (vector != null) {
                    storeEmbedding(candidate.id, vector);
                    budget--;
                }
            }
            if (vector != null) {
                double cosine = cosine(current, vector);
                if (cosine > 0) {
                    candidate.finalScore += (int) Math.round(cosine * weight);
                }
            }
        }
    }

    /** 把案例的接口/描述/根因/异常拼成一段语义文本，当前案例与历史案例用同一拼法保证可比。 */
    private String caseText(String apiPath, String description, String rootCause, String exception) {
        StringBuilder builder = new StringBuilder();
        if (!isBlank(apiPath)) {
            builder.append(apiPath).append('\n');
        }
        if (!isBlank(description)) {
            builder.append(description).append('\n');
        }
        if (!isBlank(rootCause)) {
            builder.append(rootCause).append('\n');
        }
        if (!isBlank(exception)) {
            builder.append(exception);
        }
        return builder.toString().trim();
    }

    private float[] parseStoredEmbedding(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception exception) {
            return null;
        }
    }

    private void storeEmbedding(long id, float[] vector) {
        try {
            analysisRecordMapper.updateEmbedding(id, objectMapper.writeValueAsString(vector));
        } catch (Exception exception) {
            // 回写只是缓存，失败下次再算，不影响本次召回
            log.debug("回写案例向量失败 id={}: {}", id, exception.getMessage());
        }
    }

    /** 从候选行 Map 取字符串字段，null 安全 */
    private String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    /** 余弦相似度，维度不一致(换过 embedding 模型)或零向量按 0 处理，不误配。 */
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int index = 0; index < a.length; index++) {
            dot += a[index] * b[index];
            normA += a[index] * a[index];
            normB += b[index] * b[index];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
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

    // 结论里的小节标题壳（带 markdown # 或【】装饰），抓根因时要剥掉，别把"通俗结论"标题当根因
    private static final Pattern HEADING_PREFIX = Pattern.compile(
            "^[#\\s]*【?\\s*(?:通俗结论|问题结论|证据链路|根因类型|关键证据|关键代码|建议处理人|置信度|给开发 AI 的修复提示|结论)\\s*】?[:：]?\\s*");

    /** 真实根因优先取人工确认的；没有就退回结论里第一行有正文的内容，剥掉小节标题壳，避免抓到"【通俗结论】"这种空标题。 */
    private String resolveRootCause(String actualRootCause, String conclusion) {
        if (!isBlank(actualRootCause)) {
            return trim(actualRootCause.trim(), 200);
        }
        for (String line : safe(conclusion).split("\\r?\\n")) {
            String cleaned = HEADING_PREFIX.matcher(line.trim()).replaceFirst("").trim();
            if (!cleaned.isEmpty()) {
                return trim(cleaned, 200);
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

    /** 召回候选的中间态：词法分、缓存向量、待回填的语义文本，过完语义混分再落成 SimilarCase。 */
    private static final class Candidate {
        private long id;
        private String apiPath;
        private String rootCause;
        private String keywords;
        private String confidence;
        private int lexical;
        private int finalScore;
        private String embeddingJson;
        private String embedText;
    }
}
