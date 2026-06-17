package com.tjc.bugagent.analysis;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.dbhub.DbhubClient;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates code graph, dbhub and AI evidence for bug analysis.
 */
@Service
public class AnalysisService {
    private final ProjectService projectService;
    private final CodeGraphQueryService codeGraphQueryService;
    private final DbhubClient dbhubClient;
    private final AiClient aiClient;
    private final JdbcTemplate jdbcTemplate;

    public AnalysisService(ProjectService projectService,
                           CodeGraphQueryService codeGraphQueryService,
                           DbhubClient dbhubClient,
                           AiClient aiClient,
                           JdbcTemplate jdbcTemplate) {
        this.projectService = projectService;
        this.codeGraphQueryService = codeGraphQueryService;
        this.dbhubClient = dbhubClient;
        this.aiClient = aiClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalysisResult analyze(AnalysisRequest request) {
        ProjectVersion version = resolveVersion(request);
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(request.getProjectId(), version.getId(), request.getApiPath());
        ProjectDatasource datasource = projectService.firstEnabledDatasource(request.getProjectId());
        String dbEvidence = datasource == null ? "No project datasource configured." : dbhubClient.queryEvidence(datasource.getDbhubKey(), graph.getTables());
        String evidence = buildEvidence(request, version, graph, dbEvidence);
        String conclusion = aiClient.chat(buildPrompt(evidence));
        String confidence = graph.getRouteNodes().isEmpty() ? "LOW" : "MEDIUM";
        jdbcTemplate.update(
                "insert into analysis_record(project_id, version_id, api_path, user_description, request_body, response_body, stack_trace, screenshot_paths, trace_id, request_time, conclusion, confidence, evidence_json, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                request.getProjectId(), version.getId(), request.getApiPath(), request.getUserDescription(), request.getRequestBody(), request.getResponseBody(), request.getStackTrace(), request.getScreenshotPaths(), request.getTraceId(), request.getRequestTime(), conclusion, confidence, evidence);
        Long recordId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setPlainAnswer("简单说：AI 已完成基础分析，详细原因请看“分析报告”和“证据”标签。");
        result.setConclusion(conclusion);
        result.setConfidence(confidence);
        result.setEvidenceJson(evidence);
        return result;
    }

    private ProjectVersion resolveVersion(AnalysisRequest request) {
        if (request.getVersionId() != null) {
            return projectService.getVersion(request.getVersionId());
        }
        ProjectVersion version = projectService.latestReadyVersion(request.getProjectId());
        if (version == null) {
            throw new IllegalStateException("no indexed project version found");
        }
        return version;
    }

    private String buildPrompt(String evidence) {
        return "Analyze this backend bug or business behavior issue. Return sections: conclusion, evidence, next checks, likely fix. Evidence:\n" + evidence;
    }

    private String buildEvidence(AnalysisRequest request, ProjectVersion version, CodeGraphQueryResult graph, String dbEvidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        append(builder, "apiPath", request.getApiPath());
        append(builder, "userDescription", request.getUserDescription());
        append(builder, "requestBody", request.getRequestBody());
        append(builder, "responseBody", request.getResponseBody());
        append(builder, "stackTrace", request.getStackTrace());
        append(builder, "screenshotPaths", request.getScreenshotPaths());
        append(builder, "traceId", request.getTraceId());
        append(builder, "requestTime", request.getRequestTime());
        append(builder, "versionId", String.valueOf(version.getId()));
        append(builder, "routeNodes", joinLines(graph.getRouteNodes()));
        append(builder, "relatedNodes", joinLines(graph.getRelatedNodes()));
        append(builder, "tables", String.valueOf(graph.getTables()));
        append(builder, "sqlTexts", String.valueOf(graph.getSqlTexts()));
        append(builder, "dbEvidence", dbEvidence);
        builder.append("}\n");
        return builder.toString();
    }

    private String joinLines(List<?> items) {
        StringBuilder builder = new StringBuilder();
        for (Object item : items) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(item);
        }
        return builder.toString();
    }

    private void append(StringBuilder builder, String key, String value) {
        builder.append("  \"").append(key).append("\": \"")
                .append(value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                .append("\",\n");
    }
}
