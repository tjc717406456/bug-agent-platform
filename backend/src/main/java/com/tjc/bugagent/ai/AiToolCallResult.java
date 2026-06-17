package com.tjc.bugagent.ai;

/**
 * AI 返回的工具调用结果。
 */
public class AiToolCallResult {
    private String content;
    private String toolName;
    private String argumentsJson;
    private String rawResponse;

    /**
     * 判断本轮是否拿到了标准工具调用。
     */
    public boolean hasToolCall() {
        return toolName != null && !toolName.trim().isEmpty();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }
}
