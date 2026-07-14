package com.tjc.bugagent.analysis.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具的统一契约，Schema、能力和执行逻辑只在一处声明。
 */
public interface AgentTool {

    String name();

    String description();

    Map<String, Object> properties();

    List<String> required();

    AgentToolPhase phase();

    boolean concurrencySafe();

    boolean cacheable();

    boolean contributesEvidence();

    AgentToolResult execute(AgentToolCall call, AgentToolExecutor.AgentToolContext context);
}
