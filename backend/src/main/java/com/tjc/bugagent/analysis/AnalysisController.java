package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.agent.AgentAnalysisTaskService;
import com.tjc.bugagent.analysis.agent.AgentAnalysisTaskStatus;
import com.tjc.bugagent.analysis.agent.AgentAnalysisTaskSubmitResult;
import com.tjc.bugagent.analysis.log.LogStorageService;
import com.tjc.bugagent.analysis.log.ScreenshotStorageService;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Bug 分析接口。
 */
@RestController
@RequestMapping("/analysis")
public class AnalysisController {
    private final AgentAnalysisTaskService agentAnalysisTaskService;
    private final ScreenshotStorageService screenshotStorageService;
    private final AnalysisFeedbackService analysisFeedbackService;
    private final LogStorageService logStorageService;
    private final AnalysisRecordService analysisRecordService;
    private final ObjectMapper objectMapper;

    public AnalysisController(AgentAnalysisTaskService agentAnalysisTaskService,
                              ScreenshotStorageService screenshotStorageService,
                              AnalysisFeedbackService analysisFeedbackService,
                              LogStorageService logStorageService,
                              AnalysisRecordService analysisRecordService,
                              ObjectMapper objectMapper) {
        this.agentAnalysisTaskService = agentAnalysisTaskService;
        this.screenshotStorageService = screenshotStorageService;
        this.analysisFeedbackService = analysisFeedbackService;
        this.logStorageService = logStorageService;
        this.analysisRecordService = analysisRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交带截图的异步 Agent 分析任务。
     */
    @PostMapping("/agent/tasks/screenshots")
    public ApiResponse<AgentAnalysisTaskSubmitResult> submitAgentTaskWithScreenshots(@RequestParam("request") String requestJson,
                                                                                     @RequestParam(value = "screenshots", required = false) MultipartFile[] screenshots) throws Exception {
        AnalysisRequest request = objectMapper.readValue(requestJson, AnalysisRequest.class);
        String screenshotPaths = screenshotStorageService.saveScreenshots(request.getProjectId(), screenshots);
        request.setScreenshotPaths(screenshotPaths);
        return ApiResponse.ok(agentAnalysisTaskService.submit(request));
    }

    /**
     * 提交异步接口讲解任务，只需 项目+版本+接口路径，轮询复用下方 poll 接口。
     */
    @PostMapping("/explain/tasks")
    public ApiResponse<AgentAnalysisTaskSubmitResult> submitExplainTask(@Valid @RequestBody AnalysisRequest request) {
        return ApiResponse.ok(agentAnalysisTaskService.submitExplain(request));
    }

    /**
     * 轮询异步 Agent 分析任务状态。
     */
    @PostMapping("/agent/tasks/{taskId}/poll")
    public ApiResponse<AgentAnalysisTaskStatus> getAgentTask(@PathVariable String taskId) {
        return ApiResponse.ok(agentAnalysisTaskService.getStatus(taskId));
    }

    /**
     * 手动停止正在跑的 Agent 分析/接口讲解任务。
     */
    @PostMapping("/agent/tasks/{taskId}/stop")
    public ApiResponse<String> stopAgentTask(@PathVariable String taskId) {
        agentAnalysisTaskService.requestStop(taskId);
        return ApiResponse.ok("已请求停止");
    }

    /**
     * 基于一条分析记录发起追问，返回 taskId，轮询复用 poll 接口（含流式快照）。
     */
    @PostMapping("/records/{recordId}/followup/tasks")
    public ApiResponse<AgentAnalysisTaskSubmitResult> submitFollowUp(@PathVariable Long recordId,
                                                                     @RequestBody Map<String, String> body) {
        return ApiResponse.ok(agentAnalysisTaskService.submitFollowUp(recordId, body.get("question")));
    }

    /**
     * 对一条分析记录做人工反馈（对错 + 真实根因 + 期望关键词），沉淀成回归用例。
     */
    @PutMapping("/records/{recordId}/feedback")
    public ApiResponse<String> feedback(@PathVariable Long recordId, @Valid @RequestBody AnalysisFeedbackRequest request) {
        analysisFeedbackService.saveFeedback(recordId, request);
        return ApiResponse.ok("ok");
    }

    /**
     * 上传日志文件（限大小），保存后返回 logId；分析时后端凭 logId 直接读文件，无需前端回传全文。
     */
    @PostMapping("/logs/upload")
    public ApiResponse<String> uploadLog(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(logStorageService.save(file));
    }

    /**
     * 分析历史列表（按项目、接口筛，分页）。
     */
    @GetMapping("/records")
    public ApiResponse<AnalysisRecordPage> listRecords(@RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) String apiPath,
                                                       @RequestParam(required = false) String recordType,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(analysisRecordService.list(projectId, apiPath, recordType, page, size));
    }

    /**
     * 单条分析记录详情（含证据和已有标注）。
     */
    @GetMapping("/records/{recordId}")
    public ApiResponse<AnalysisRecord> getRecord(@PathVariable Long recordId) {
        return ApiResponse.ok(analysisRecordService.get(recordId));
    }

    /**
     * 批量删除分析记录。
     */
    @PostMapping("/records/batch-delete")
    public ApiResponse<String> batchDeleteRecords(@RequestBody List<Long> ids) {
        analysisRecordService.deleteByIds(ids);
        return ApiResponse.ok("ok");
    }
}
