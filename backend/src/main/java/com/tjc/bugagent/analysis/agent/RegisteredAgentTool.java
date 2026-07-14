package com.tjc.bugagent.analysis.agent;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 用元数据和执行函数组装一个工具，避免每个只读工具重复写样板类。
 */
public class RegisteredAgentTool implements AgentTool {
    private final String name;
    private final String description;
    private final Map<String, Object> properties;
    private final List<String> required;
    private final AgentToolPhase phase;
    private final boolean concurrencySafe;
    private final boolean cacheable;
    private final boolean contributesEvidence;
    private final BiFunction<AgentToolCall, AgentToolExecutor.AgentToolContext, AgentToolResult> executor;

    public RegisteredAgentTool(String name, String description, Map<String, Object> properties, List<String> required,
                               AgentToolPhase phase, boolean concurrencySafe, boolean cacheable,
                               boolean contributesEvidence,
                               BiFunction<AgentToolCall, AgentToolExecutor.AgentToolContext, AgentToolResult> executor) {
        this.name = name;
        this.description = description;
        this.properties = properties;
        this.required = required;
        this.phase = phase;
        this.concurrencySafe = concurrencySafe;
        this.cacheable = cacheable;
        this.contributesEvidence = contributesEvidence;
        this.executor = executor;
    }

    public String name() { return name; }
    public String description() { return description; }
    public Map<String, Object> properties() { return properties; }
    public List<String> required() { return required; }
    public AgentToolPhase phase() { return phase; }
    public boolean concurrencySafe() { return concurrencySafe; }
    public boolean cacheable() { return cacheable; }
    public boolean contributesEvidence() { return contributesEvidence; }

    public AgentToolResult execute(AgentToolCall call, AgentToolExecutor.AgentToolContext context) {
        return executor.apply(call, context);
    }
}
