package com.tjc.bugagent.analysis.agent;

/**
 * Agent 运行停止原因。
 */
public enum AgentStopReason {
    FINISH_TOOL,
    MAX_ITERATIONS,
    TOKEN_BUDGET,
    TOOL_BUDGET,
    CONTINUOUS_TOOL_FAILURES,
    CANCELLED,
    MODEL_ERROR,
    TOOL_ERROR,
    INTERNAL_ERROR,
    INTERRUPTED
}
