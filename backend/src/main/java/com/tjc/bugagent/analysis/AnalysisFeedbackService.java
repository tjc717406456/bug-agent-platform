package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存分析记录的人工反馈，给评估飞轮攒 ground truth。
 */
@Service
public class AnalysisFeedbackService {
    private final AnalysisRecordMapper analysisRecordMapper;
    private final ObjectMapper objectMapper;

    public AnalysisFeedbackService(AnalysisRecordMapper analysisRecordMapper, ObjectMapper objectMapper) {
        this.analysisRecordMapper = analysisRecordMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 标注某条分析记录的对错与正确根因，关键词序列化成 JSON 存起来。
     */
    public void saveFeedback(Long recordId, AnalysisFeedbackRequest request) {
        int updated = analysisRecordMapper.updateFeedback(recordId, request.getVerdict(), request.getActualRootCause(),
                toJson(request.getExpectKeywords()), request.getNote());
        if (updated == 0) {
            throw new IllegalArgumentException("分析记录不存在: " + recordId);
        }
    }

    private String toJson(List<String> keywords) {
        List<String> safe = keywords == null ? new ArrayList<String>() : keywords;
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception exception) {
            return "[]";
        }
    }
}
