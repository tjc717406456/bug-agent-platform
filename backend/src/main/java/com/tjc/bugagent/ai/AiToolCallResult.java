package com.tjc.bugagent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 返回的工具调用结果。一轮可能包含多个工具调用。
 */
public class AiToolCallResult {
    private String content;
    private String toolName;
    private String argumentsJson;
    private String rawResponse;
    private List<AiToolCall> toolCalls = new ArrayList<AiToolCall>();
    // 模型本轮返回的原始 assistant message，多轮对话时原样回填，保证 tool_calls 与 tool 结果配对
    private Map<String, Object> assistantMessage;
    // 本次调用消耗的 token（从响应 usage 取，流式且未开 usage 时为 0），用于成本追踪
    private int totalTokens;
    // AI 调用真失败（网关重试耗尽/未配置），content 是兜底错误文案而非模型输出；上层据此中止而非当报告收口
    private boolean failed;

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Map<String, Object> getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(Map<String, Object> assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    /**
     * 判断本轮是否拿到了标准工具调用。
     */
    public boolean hasToolCall() {
        return !toolCalls.isEmpty();
    }

    public List<AiToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<AiToolCall> toolCalls) {
        this.toolCalls = toolCalls == null ? new ArrayList<AiToolCall>() : toolCalls;
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
