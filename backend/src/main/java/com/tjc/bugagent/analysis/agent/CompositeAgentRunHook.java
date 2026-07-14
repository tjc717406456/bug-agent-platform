package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合多个 Hook；单个观测 Hook 失败不影响 Agent 主流程。
 */
public class CompositeAgentRunHook implements AgentRunHook {
    private static final Logger log = LoggerFactory.getLogger(CompositeAgentRunHook.class);
    private final List<AgentRunHook> hooks;

    public CompositeAgentRunHook(List<AgentRunHook> hooks) {
        this.hooks = hooks == null ? new ArrayList<AgentRunHook>() : hooks;
    }

    public void beforeRun(AgentRunContext context) { each(hook -> hook.beforeRun(context)); }
    public void beforeIteration(AgentRunContext context) { each(hook -> hook.beforeIteration(context)); }
    public void afterModelResponse(AgentRunContext context, AiToolCallResult response) { each(hook -> hook.afterModelResponse(context, response)); }
    public void beforeTools(AgentRunContext context, List<AgentToolCall> calls) { each(hook -> hook.beforeTools(context, calls)); }
    public void afterTool(AgentRunContext context, AgentToolCall call, AgentToolResult result, long elapsedMs) { each(hook -> hook.afterTool(context, call, result, elapsedMs)); }
    public void afterIteration(AgentRunContext context) { each(hook -> hook.afterIteration(context)); }
    public void afterRun(AgentRunContext context) { each(hook -> hook.afterRun(context)); }
    public void onError(AgentRunContext context, RuntimeException exception) { each(hook -> hook.onError(context, exception)); }

    private void each(HookCall call) {
        for (AgentRunHook hook : hooks) {
            try {
                call.invoke(hook);
            } catch (Exception exception) {
                log.warn("Agent Hook 执行失败: {}", hook.getClass().getSimpleName(), exception);
            }
        }
    }

    private interface HookCall {
        void invoke(AgentRunHook hook);
    }
}
