package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.mapper.AnalysisRecordInsert;
import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.analysis.verify.AnalysisAutoVerifier;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * analysis_record 表写入入口：保存一次 Agent 分析的结论、证据和自动验证结果，回拿记录 id。
 */
@Repository
public class AnalysisRecordRepository {

    private final AnalysisRecordMapper analysisRecordMapper;
    private final ObjectMapper objectMapper;

    public AnalysisRecordRepository(AnalysisRecordMapper analysisRecordMapper, ObjectMapper objectMapper) {
        this.analysisRecordMapper = analysisRecordMapper;
        this.objectMapper = objectMapper;
    }

    public Long save(AnalysisRequest request, ProjectVersion version, String conclusion, String confidence,
                     String evidence, AnalysisAutoVerifier.Result autoVerify, int roundsCount, int totalTokens) {
        String verifyKeywords = autoVerify.getKeywords().isEmpty() ? null : toJsonKeywords(autoVerify.getKeywords());
        AnalysisRecordInsert record = new AnalysisRecordInsert();
        record.setRecordType("ANALYSIS");
        record.setProjectId(request.getProjectId());
        record.setVersionId(version.getId());
        record.setApiPath(request.getApiPath());
        record.setUserDescription(request.getUserDescription());
        record.setRequestBody(request.getRequestBody());
        record.setResponseBody(request.getResponseBody());
        record.setStackTrace(request.getStackTrace());
        record.setScreenshotPaths(request.getScreenshotPaths());
        record.setTraceId(request.getTraceId());
        record.setRequestTime(request.getRequestTime());
        record.setConclusion(conclusion);
        record.setConfidence(confidence);
        record.setEvidenceJson(evidence);
        record.setAutoVerify(autoVerify.getStatus());
        record.setAutoVerifyKeywords(verifyKeywords);
        record.setRoundsCount(roundsCount);
        record.setTotalTokens(totalTokens);
        record.setCreatedBy(request.getOwnerId());
        analysisRecordMapper.insert(record);
        return record.getId();
    }

    /**
     * 保存一次接口讲解：没有置信度/自动验证这些 bug 专属字段，落库后同样支持追问与历史回看。
     */
    public Long saveExplain(AnalysisRequest request, ProjectVersion version, String conclusion,
                            String evidence, int roundsCount, int totalTokens) {
        AnalysisRecordInsert record = new AnalysisRecordInsert();
        record.setRecordType("EXPLAIN");
        record.setProjectId(request.getProjectId());
        record.setVersionId(version.getId());
        record.setApiPath(request.getApiPath());
        record.setUserDescription(request.getUserDescription());
        record.setConclusion(conclusion);
        record.setConfidence("");
        record.setEvidenceJson(evidence);
        record.setRoundsCount(roundsCount);
        record.setTotalTokens(totalTokens);
        record.setCreatedBy(request.getOwnerId());
        analysisRecordMapper.insert(record);
        return record.getId();
    }

    private String toJsonKeywords(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords);
        } catch (Exception exception) {
            return null;
        }
    }
}
