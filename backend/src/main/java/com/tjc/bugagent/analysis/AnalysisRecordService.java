package com.tjc.bugagent.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询分析历史记录，支撑历史页和延迟标注。
 */
@Service
public class AnalysisRecordService {
    private final JdbcTemplate jdbcTemplate;

    public AnalysisRecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按项目、接口筛分析历史，分页返回精简字段（不带大体积证据）+ 总条数。
     */
    public AnalysisRecordPage list(Long projectId, String apiPath, int page, int size) {
        int limit = size <= 0 || size > 200 ? 20 : size;
        int offset = Math.max(0, page) * limit;
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<Object>();
        if (projectId != null) {
            where.append(" and project_id = ?");
            params.add(projectId);
        }
        if (apiPath != null && !apiPath.trim().isEmpty()) {
            where.append(" and api_path like ?");
            params.add("%" + apiPath.trim() + "%");
        }
        Object[] args = params.toArray();
        Long total = jdbcTemplate.queryForObject("select count(*) from analysis_record" + where, Long.class, args);
        String sql = "select id, project_id, api_path, confidence, auto_verify, feedback_verdict, "
                + "substring(conclusion, 1, 300) as conclusion, created_at from analysis_record" + where
                + " order by id desc limit " + limit + " offset " + offset;
        List<AnalysisRecord> records = jdbcTemplate.query(sql, new SummaryMapper(), args);
        return new AnalysisRecordPage(records, total == null ? 0 : total);
    }

    /**
     * 批量删除分析记录。
     */
    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.update("delete from analysis_record where id in (" + placeholders + ")", ids.toArray());
    }

    /**
     * 取单条完整记录（含证据和已有标注），给详情和延迟标注用。
     */
    public AnalysisRecord get(Long id) {
        List<AnalysisRecord> list = jdbcTemplate.query(
                "select id, project_id, api_path, user_description, conclusion, confidence, evidence_json, "
                        + "auto_verify, feedback_verdict, actual_root_cause, expect_keywords, created_at "
                        + "from analysis_record where id = ?",
                new DetailMapper(), id);
        return list.isEmpty() ? null : list.get(0);
    }

    private static class SummaryMapper implements RowMapper<AnalysisRecord> {
        @Override
        public AnalysisRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnalysisRecord record = new AnalysisRecord();
            record.setId(rs.getLong("id"));
            record.setProjectId(rs.getLong("project_id"));
            record.setApiPath(rs.getString("api_path"));
            record.setConfidence(rs.getString("confidence"));
            record.setAutoVerify(rs.getString("auto_verify"));
            record.setFeedbackVerdict(rs.getString("feedback_verdict"));
            record.setConclusion(rs.getString("conclusion"));
            record.setCreatedAt(rs.getString("created_at"));
            return record;
        }
    }

    private static class DetailMapper implements RowMapper<AnalysisRecord> {
        @Override
        public AnalysisRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnalysisRecord record = new AnalysisRecord();
            record.setId(rs.getLong("id"));
            record.setProjectId(rs.getLong("project_id"));
            record.setApiPath(rs.getString("api_path"));
            record.setUserDescription(rs.getString("user_description"));
            record.setConclusion(rs.getString("conclusion"));
            record.setConfidence(rs.getString("confidence"));
            record.setEvidenceJson(rs.getString("evidence_json"));
            record.setAutoVerify(rs.getString("auto_verify"));
            record.setFeedbackVerdict(rs.getString("feedback_verdict"));
            record.setActualRootCause(rs.getString("actual_root_cause"));
            record.setExpectKeywords(rs.getString("expect_keywords"));
            record.setCreatedAt(rs.getString("created_at"));
            return record;
        }
    }
}
