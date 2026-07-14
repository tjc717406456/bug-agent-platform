package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;

import java.util.List;

/**
 * Agent 运行生命周期扩展点。
 */
public interface AgentRunHook {
    default void beforeRun(AgentRunContext context) { }
    default void beforeIteration(AgentRunContext context) { }
    default void afterModelResponse(AgentRunContext context, AiToolCallResult response) { }
    default void beforeTools(AgentRunContext context, List<AgentToolCall> calls) { }
    default void afterTool(AgentRunContext context, AgentToolCall call, AgentToolResult result, long elapsedMs) { }
    default void afterIteration(AgentRunContext context) { }
    default void afterRun(AgentRunContext context) { }
    default void onError(AgentRunContext context, RuntimeException exception) { }
}
