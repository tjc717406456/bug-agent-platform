package com.tjc.bugagent.analysis;

/**
 * Agent 分析过程的进度回调，让前端能实时看到每轮在干什么，而不是干等黑盒。
 * 同时承载取消信号：循环每轮开头查 isCancelled，true 即中断（并行假设分支共用同一实例，天然可见）。
 */
@FunctionalInterface
public interface AnalysisProgressListener {
    AnalysisProgressListener NOOP = step -> {
    };

    void onStep(String step);

    /** 是否已被用户手动停止；默认 false，异步任务 runner 会覆盖成查取消标记。 */
    default boolean isCancelled() {
        return false;
    }

    /** 收口报告流式生成的累计快照，前端轮询取到后渐进渲染；默认丢弃。 */
    default void onPartialReport(String partial) {
    }
}
