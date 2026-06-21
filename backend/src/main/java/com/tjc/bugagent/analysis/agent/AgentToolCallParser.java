package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.ai.AiToolCall;
import com.tjc.bugagent.ai.AiToolCallResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.stringValue;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 把模型返回解析成可执行的工具调用。优先吃标准 tool_calls，
 * 拿不到再退回单次 JSON/文本解析，各种异常都兜底成一个 finish 调用，保证主流程不空转。
 */
@Component
public class AgentToolCallParser {

    private final ObjectMapper objectMapper;

    public AgentToolCallParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析本轮全部工具调用；无标准 tool_calls 时回退到单次 JSON/文本解析。
     */
    public List<AgentToolCall> parseToolCalls(AiToolCallResult aiResult) {
        List<AgentToolCall> calls = new ArrayList<AgentToolCall>();
        if (aiResult != null && aiResult.hasToolCall()) {
            for (AiToolCall raw : aiResult.getToolCalls()) {
                if (isBlank(raw.getName())) {
                    continue;
                }
                AgentToolCall call = new AgentToolCall();
                call.setAction(raw.getName());
                call.setToolCallId(raw.getId());
                // 思考过程走 message content（多个调用共用本轮正文）
                call.setThought(aiResult.getContent());
                call.setArguments(parseArguments(raw.getArgumentsJson()));
                calls.add(call);
            }
            if (!calls.isEmpty()) {
                return calls;
            }
        }
        calls.add(parseToolCall(aiResult));
        return calls;
    }

    public AgentToolCall findFinish(List<AgentToolCall> calls) {
        for (AgentToolCall call : calls) {
            if ("finish".equals(call.getAction())) {
                return call;
            }
        }
        return null;
    }

    public AgentToolCall finishCall(String report) {
        AgentToolCall call = new AgentToolCall();
        call.setThought("模型输出无法继续结构化执行，直接收敛");
        call.setAction("finish");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("report", report);
        call.setArguments(arguments);
        return call;
    }

    /**
     * 模型没按协议给工具调用、只能从文本兜底成的 finish；打降级标，循环可先纠偏重试再决定收口。
     */
    public AgentToolCall degradedFinish(String report) {
        AgentToolCall call = finishCall(report);
        call.setDegraded(true);
        return call;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (isBlank(argumentsJson)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private AgentToolCall parseToolCall(AiToolCallResult aiResult) {
        if (aiResult != null && aiResult.hasToolCall()) {
            return parseStructuredToolCall(aiResult);
        }
        String aiResponse = aiResult == null ? null : aiResult.getContent();
        // AI 调用本身失败时，失败串里常夹着错误 JSON，别去抠它误报"缺少 action"，直接给干净结论
        if (isAiFailureContent(aiResponse)) {
            return finishCall("AI 调用失败，无法继续分析：" + trim(safeText(aiResponse), 300));
        }
        String json = extractJsonObject(aiResponse);
        if (json == null) {
            return degradedFinish("AI 未返回 JSON，按当前内容收敛：\n" + aiResponse);
        }
        try {
            Map<String, Object> values = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            AgentToolCall call = new AgentToolCall();
            call.setThought(stringValue(values.get("thought")));
            call.setAction(stringValue(values.get("action")));
            Object arguments = values.get("arguments");
            if (arguments instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> argMap = (Map<String, Object>) arguments;
                call.setArguments(argMap);
            }
            if (isBlank(call.getAction())) {
                return degradedFinish("AI JSON 缺少 action，原始内容：\n" + aiResponse);
            }
            return call;
        } catch (Exception exception) {
            return degradedFinish("AI JSON 解析失败: " + exception.getMessage() + "\n原始内容：\n" + aiResponse);
        }
    }

    private AgentToolCall parseStructuredToolCall(AiToolCallResult aiResult) {
        try {
            Map<String, Object> arguments = objectMapper.readValue(aiResult.getArgumentsJson(), new TypeReference<Map<String, Object>>() {});
            AgentToolCall call = new AgentToolCall();
            call.setAction(aiResult.getToolName());
            // 思考过程不再混进工具参数，从模型 message content 读取（拿不到就留空）
            call.setThought(aiResult.getContent());
            call.setArguments(arguments);
            return call;
        } catch (Exception exception) {
            return finishCall("AI tool_calls 参数解析失败: " + exception.getMessage() + "\n原始内容：\n" + aiResult.getRawResponse());
        }
    }

    /**
     * 识别 AiClient 返回的失败/未配置内容，这些不是模型输出，不该当 JSON 解析。
     */
    private boolean isAiFailureContent(String content) {
        if (isBlank(content)) {
            return false;
        }
        return content.startsWith("AI call failed") || content.startsWith("AI is not configured")
                || content.startsWith("AI returned");
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
