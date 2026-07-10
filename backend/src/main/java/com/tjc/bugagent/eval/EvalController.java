package com.tjc.bugagent.eval;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评估跑批接口：跑用例集给分析准确率打分。
 *
 * <p>整个 /eval 前缀已被 AuthInterceptor 限定为管理员可访问：跑批会消耗大量 token，
 * 且用例池取自全部用户的标注记录。
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
     * 裸模型基线：同一批用例只喂报错给模型单次作答，不走代理。
     */
    @PostMapping("/run-baseline")
    public ApiResponse<EvalSummary> runBaseline(@RequestBody(required = false) List<EvalCase> cases) {
        return ApiResponse.ok(evalService.runBaseline(cases));
    }

    /**
     * A/B 对比：同一批用例分别跑裸模型和全代理，返回 {baseline, agent} 两份汇总。
     */
    @PostMapping("/ab")
    public ApiResponse<Map<String, EvalSummary>> ab(@RequestBody(required = false) List<EvalCase> cases) {
        return ApiResponse.ok(evalService.runAb(cases));
    }

    /**
     * 飞轮跑批：用人工标注过的真实 bug 当回归集，跑一遍看准确率。
     */
    @PostMapping("/run-feedback")
    public ApiResponse<EvalSummary> runFromFeedback() {
        return ApiResponse.ok(evalService.runFromFeedback());
    }
}
