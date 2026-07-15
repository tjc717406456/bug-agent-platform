package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;

import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * 领域 Workflow 注入 Runner 的差异化策略。
 */
public interface AgentRunPolicy {
    default void beforeIteration(AgentRunContext context) { }
    default void afterModelResponse(AgentRunContext context, AiToolCallResult response) { }
    /** 按 Workflow 控制本轮真正执行的工具；默认原样放行。 */
    default List<AgentToolCall> filterToolCalls(AgentRunContext context, List<AgentToolCall> calls) { return calls; }
    default AgentToolCall resolveFinish(AgentRunContext context, List<AgentToolCall> calls, AgentToolCall finish) { return finish; }
    AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult response, AgentToolCall finish);
    default AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult response,
                                         List<AgentToolCall> calls, List<AgentToolResult> results) {
        return AgentRunDirective.continueRun();
    }
    String finalizeRun(AgentRunContext context);
    default Map<String, Object> snapshotState(AgentRunContext context) { return Collections.emptyMap(); }
    default void restoreState(AgentRunContext context, Map<String, Object> state) { }
}
