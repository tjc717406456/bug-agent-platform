package com.tjc.bugagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ObjectMapper objectMapper;

    public AnalysisController(AnalysisService analysisService,
                              AgentAnalysisService agentAnalysisService,
                              AgentAnalysisTaskService agentAnalysisTaskService,
                              ScreenshotStorageService screenshotStorageService,
                              ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.agentAnalysisService = agentAnalysisService;
        this.agentAnalysisTaskService = agentAnalysisTaskService;
        this.screenshotStorageService = screenshotStorageService;
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
     * 执行同步 Agent 分析，保留给旧前端和接口调用方。
     */
    @PostMapping("/agent")
    public ApiResponse<AnalysisResult> analyzeWithAgent(@Valid @RequestBody AnalysisRequest request) {
        return ApiResponse.ok(agentAnalysisService.analyze(request));
    }

    /**
     * 执行带截图的同步 Agent 分析。
     */
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
}
