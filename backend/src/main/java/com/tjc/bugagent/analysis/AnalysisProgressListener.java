package com.tjc.bugagent.analysis;

/**
 * Agent 分析过程的进度回调，让前端能实时看到每轮在干什么，而不是干等黑盒。
 */
@FunctionalInterface
public interface AnalysisProgressListener {
    AnalysisProgressListener NOOP = step -> {
    };

    void onStep(String step);
}
