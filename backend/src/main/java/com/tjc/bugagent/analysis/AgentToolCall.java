package com.tjc.bugagent.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 每轮输出的结构化工具调用。
 */
public class AgentToolCall {
    private String thought;
    private String action;
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();

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
