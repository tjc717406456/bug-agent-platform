package com.tjc.bugagent.ai;

/**
 * 模型一次返回的单个工具调用。一轮可包含多个。
 */
public class AiToolCall {
    private String id;
    private String name;
    private String argumentsJson;

    public AiToolCall() {
    }

    public AiToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson;
    }
}
