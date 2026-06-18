package com.tjc.bugagent.eval;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评估跑批接口：跑用例集给分析准确率打分。
 */
@RestController
@RequestMapping("/eval")
public class EvalController {
    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /**
     * 跑评估。body 传用例数组则用传入的，留空则读配置的用例文件。
     */
    @PostMapping("/run")
    public ApiResponse<EvalSummary> run(@RequestBody(required = false) List<EvalCase> cases) {
        return ApiResponse.ok(evalService.run(cases));
    }

    /**
     * 飞轮跑批：用人工标注过的真实 bug 当回归集，跑一遍看准确率。
     */
    @PostMapping("/run-feedback")
    public ApiResponse<EvalSummary> runFromFeedback() {
        return ApiResponse.ok(evalService.runFromFeedback());
    }
}
