package com.tjc.bugagent.analysis;

import com.tjc.bugagent.analysis.agent.AgentRunCheckpoint;
import com.tjc.bugagent.analysis.agent.ProjectExecutionScope;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.DatasourceSelection;

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

    /** 完整轮次检查点；异步任务会持久化，普通同步调用可忽略。 */
    default void onCheckpoint(AgentRunCheckpoint checkpoint) {
    }

    /** 由已鉴权的任务入口创建执行范围，异步任务可补上 taskId 与 ownerId。 */
    default ProjectExecutionScope executionScope(Long projectId, Long versionId, ProjectDatasource datasource) {
        return ProjectExecutionScope.create(null, null, projectId, versionId, datasource);
    }

    /** 创建带结构和业务数据隔离的执行范围。 */
    default ProjectExecutionScope executionScope(Long projectId, Long versionId, DatasourceSelection selection) {
        return ProjectExecutionScope.create(null, null, projectId, versionId, selection);
    }

    /** 需要恢复时返回最近完整轮次检查点。 */
    default AgentRunCheckpoint resumeCheckpoint() {
        return null;
    }
}
