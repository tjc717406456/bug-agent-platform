package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 维护与模型的 OpenAI 对话消息：构造消息、按 tool_call 协议回填 assistant 与 tool 结果。
 * OpenAI 要求每个 tool_call 都要有配对 tool 消息，回填不全下一轮请求会被服务端拒。
 */
@Component
public class AgentConversation {

    private final AppProperties appProperties;

    public AgentConversation(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 回填模型本轮的 assistant 消息（含 tool_calls）；拿不到原始消息时退化成普通文本消息。
     */
    public void appendAssistantMessage(List<Map<String, Object>> messages, AiToolCallResult aiResult) {
        Map<String, Object> assistant = aiResult.getAssistantMessage();
        if (assistant != null) {
            messages.add(assistant);
        } else {
            messages.add(message("assistant", safe(aiResult.getContent())));
        }
    }

    /**
     * 按 tool_call id 逐条回填工具结果。缺失的兜底成"工具未执行"，避免下一轮请求被服务端拒绝。
     */
    public void appendToolMessages(List<Map<String, Object>> messages, AiToolCallResult aiResult,
                                   List<AgentToolCall> calls, List<AgentToolResult> results) {
        Map<String, String> contentById = new HashMap<String, String>();
        for (int i = 0; i < calls.size(); i++) {
            String id = calls.get(i).getToolCallId();
            if (id != null) {
                contentById.put(id, toolResultContent(results.get(i)));
            }
        }
        if (aiResult.getToolCalls().isEmpty()) {
            return;
        }
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", contentById.getOrDefault(id, "工具未执行"));
            messages.add(toolMessage);
        }
    }

    private String toolResultContent(AgentToolResult result) {
        StringBuilder content = new StringBuilder();
        content.append(result.isOk() ? "成功" : "失败").append(": ").append(safe(result.getSummary()));
        String evidence = safe(result.getEvidence());
        if (!evidence.isEmpty()) {
            content.append("\n").append(trim(evidence, appProperties.getAgent().getToolResultLimit()));
        }
        return content.toString();
    }

    /**
     * 闭合被拦下的本轮全部 tool_call，满足"每个 tool_call 必须有 tool 响应"的协议。
     * finish 给复核提示，同轮夹带的其他调用统一标暂缓。
     */
    public void appendFinishHold(List<Map<String, Object>> messages, AiToolCallResult aiResult, AgentToolCall finish) {
        String finishId = finish.getToolCallId();
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", id.equals(finishId)
                    ? "结论已收到，先做独立复核再决定是否采纳。"
                    : "本轮先复核初步结论，该调用暂缓。");
            messages.add(toolMessage);
        }
    }
}
