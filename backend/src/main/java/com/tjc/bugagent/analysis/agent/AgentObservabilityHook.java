package com.tjc.bugagent.analysis.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认运行观测 Hook，统一记录停止原因、轮次、Token 和工具事件数量。
 */
@Component
public class AgentObservabilityHook implements AgentRunHook {
    private static final Logger log = LoggerFactory.getLogger(AgentObservabilityHook.class);
    private static final String STARTED_AT = AgentObservabilityHook.class.getName() + ".startedAt";

    @Override
    public void beforeRun(AgentRunContext context) {
        context.getAttributes().put(STARTED_AT, System.currentTimeMillis());
    }

    @Override
    public void afterRun(AgentRunContext context) {
        Object startedAt = context.getAttributes().get(STARTED_AT);
        long elapsedMs = startedAt instanceof Long ? System.currentTimeMillis() - (Long) startedAt : -1L;
        log.info("Agent Runner 完成 · stopReason={} · 轮次={} · token={} · 工具事件={} · 耗时={}ms",
                context.getStopReason(), context.getIteration(), context.getTotalTokens(),
                context.getToolEvents().size(), elapsedMs);
    }

    @Override
    public void onError(AgentRunContext context, RuntimeException exception) {
        log.warn("Agent Runner 异常 · 轮次={} · token={} · 工具事件={}", context.getIteration(),
                context.getTotalTokens(), context.getToolEvents().size(), exception);
    }
}
