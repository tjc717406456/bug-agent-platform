package com.tjc.bugagent.analysis.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 每轮输出的结构化工具调用。
 */
public class AgentToolCall {
    private String thought;
    private String action;
    // 对应 OpenAI tool_call 的 id，多轮回填 tool 结果时按它配对
    private String toolCallId;
    // 模型没按协议给工具调用、被迫从文本兜底成的 finish；循环可据此先纠偏重试再决定是否收口
    private boolean degraded;
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? new LinkedHashMap<String, Object>() : arguments;
    }

    /**
     * 读取字符串参数。
     */
    public String stringArg(String name) {
        Object value = arguments.get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 读取 Long 参数。
     */
    public Long longArg(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
