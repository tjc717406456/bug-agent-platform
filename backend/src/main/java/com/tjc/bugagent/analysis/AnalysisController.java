package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

/**
 * Bug 分析接口。
 */
@RestController
@RequestMapping("/analysis")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final AgentAnalysisService agentAnalysisService;
    private final AgentAnalysisTaskService agentAnalysisTaskService;
    private final ScreenshotStorageService screenshotStorageService;
    private final AnalysisFeedbackService analysisFeedbackService;
    private final LogStorageService logStorageService;
    private final ObjectMapper objectMapper;

    public AnalysisController(AnalysisService analysisService,
                              AgentAnalysisService agentAnalysisService,
                              AgentAnalysisTaskService agentAnalysisTaskService,
                              ScreenshotStorageService screenshotStorageService,
                              AnalysisFeedbackService analysisFeedbackService,
                              LogStorageService logStorageService,
                              ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.agentAnalysisService = agentAnalysisService;
        this.agentAnalysisTaskService = agentAnalysisTaskService;
        this.screenshotStorageService = screenshotStorageService;
        this.analysisFeedbackService = analysisFeedbackService;
        this.logStorageService = logStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行普通分析。
     */
    @PostMapping
    public ApiResponse<AnalysisResult> analyze(@Valid @RequestBody AnalysisRequest request) {
        return ApiResponse.ok(analysisService.analyze(request));
    }

    /**
     * 同步 Agent 分析，单次最多数轮 LLM 往返会长期占用 HTTP 线程，仅保留兼容旧调用方；
     * 新接入请走异步任务 {@code /agent/tasks} + 轮询，别用这个。
     */
    @Deprecated
    @PostMapping("/agent")
    public ApiResponse<AnalysisResult> analyzeWithAgent(@Valid @RequestBody AnalysisRequest request) {
        return ApiResponse.ok(agentAnalysisService.analyze(request));
    }

    /**
     * 带截图的同步 Agent 分析，同样占 HTTP 线程，建议改用异步任务接口。
     */
    @Deprecated
    @PostMapping("/agent/screenshots")
    public ApiResponse<AnalysisResult> analyzeWithAgentAndScreenshots(@RequestParam("request") String requestJson,
                                                                       @RequestParam(value = "screenshots", required = false) MultipartFile[] screenshots) throws Exception {
        AnalysisRequest request = objectMapper.readValue(requestJson, AnalysisRequest.class);
        String screenshotPaths = screenshotStorageService.saveScreenshots(request.getProjectId(), screenshots);
        request.setScreenshotPaths(screenshotPaths);
        return ApiResponse.ok(agentAnalysisService.analyze(request));
    }

    /**
     * 提交异步 Agent 分析任务。
     */
    @PostMapping("/agent/tasks")
    public ApiResponse<AgentAnalysisTaskSubmitResult> submitAgentTask(@Valid @RequestBody AnalysisRequest request) {
        return ApiResponse.ok(agentAnalysisTaskService.submit(request));
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
     * 轮询异步 Agent 分析任务状态。
     */
    @PostMapping("/agent/tasks/{taskId}/poll")
    public ApiResponse<AgentAnalysisTaskStatus> getAgentTask(@PathVariable String taskId) {
        return ApiResponse.ok(agentAnalysisTaskService.getStatus(taskId));
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
     * 上传日志文件（限大小），保存后读出文本返回，供分析时自动抠堆栈、SQL、traceId。
     */
    @PostMapping("/logs/upload")
    public ApiResponse<String> uploadLog(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(logStorageService.saveAndRead(file));
    }
}
