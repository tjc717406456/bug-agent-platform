package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.verify.AnalysisAutoVerifier;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * analysis_record 表写入入口：保存一次 Agent 分析的结论、证据和自动验证结果，回拿记录 id。
 */
@Repository
public class AnalysisRecordRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisRecordRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Long save(AnalysisRequest request, ProjectVersion version, String conclusion, String confidence,
                     String evidence, AnalysisAutoVerifier.Result autoVerify) {
        String verifyKeywords = autoVerify.getKeywords().isEmpty() ? null : toJsonKeywords(autoVerify.getKeywords());
        jdbcTemplate.update(
                "insert into analysis_record(project_id, version_id, api_path, user_description, request_body, response_body, stack_trace, screenshot_paths, trace_id, request_time, conclusion, confidence, evidence_json, auto_verify, auto_verify_keywords, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                request.getProjectId(), version.getId(), request.getApiPath(),
                request.getUserDescription(), request.getRequestBody(), request.getResponseBody(), request.getStackTrace(),
                request.getScreenshotPaths(), request.getTraceId(), request.getRequestTime(), conclusion, confidence, evidence,
                autoVerify.getStatus(), verifyKeywords);
        return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
    }

    private String toJsonKeywords(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords);
        } catch (Exception exception) {
            return null;
        }
    }
}
