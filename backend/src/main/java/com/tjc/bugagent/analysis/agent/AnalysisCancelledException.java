package com.tjc.bugagent.analysis.agent;

/**
 * 用户手动停止分析时抛出，循环在轮间检测到取消信号即中断；
 * 由任务 runner 捕获后把任务状态置为 CANCELLED，不落库、不当失败处理。
 */
public class AnalysisCancelledException extends RuntimeException {
    public AnalysisCancelledException() {
        super("分析已被手动停止");
    }
}
