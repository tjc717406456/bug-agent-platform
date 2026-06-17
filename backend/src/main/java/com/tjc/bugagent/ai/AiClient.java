package com.tjc.bugagent.ai;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口。
 */
@Service
public class AiClient {
    private final AiConfigService aiConfigService;
    private final RestTemplate restTemplate;

    public AiClient(AiConfigService aiConfigService, RestTemplate restTemplate) {
        this.aiConfigService = aiConfigService;
        this.restTemplate = restTemplate;
    }

    /**
     * 发送普通对话请求，保留给 AI 配置测试和旧分析流程使用。
     */
    public String chat(String prompt) {
        AiToolCallResult result = chatWithTools(prompt, null);
        return result.getContent();
    }

    /**
     * 发送带工具定义的对话请求，优先让模型返回标准 tool_calls。
     */
    public AiToolCallResult chatWithTools(String prompt, List<Map<String, Object>> tools) {
        AiToolCallResult result = new AiToolCallResult();
        AiConfig config = aiConfigService.getEnabledConfig();
        if (config == null) {
            result.setContent("AI is not configured. The report is generated from local code graph evidence only.");
            return result;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        body.put("messages", buildMessages(prompt));
        body.put("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        try {
            String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            System.out.println("======= AI请求开始 =======");
            System.out.println("请求地址: " + url);
            System.out.println("模型名称: " + config.getModelName());
            System.out.println("API Key前缀: " + maskApiKey(config.getApiKey()));
            System.out.println("请求体: " + body);

            Map response = restTemplate.postForObject(url, new HttpEntity<Map<String, Object>>(body, headers), Map.class);

            System.out.println("响应体: " + response);
            System.out.println("======= AI请求结束 =======");

            result.setRawResponse(String.valueOf(response));
            parseResponse(response, result);
            return result;
        } catch (Exception exception) {
            System.err.println("======= AI请求失败 =======");
            System.err.println("错误信息: " + exception.getMessage());
            System.err.println("错误类型: " + exception.getClass().getName());
            exception.printStackTrace();
            System.err.println("=========================");
            result.setContent("AI call failed: " + exception.getMessage());
            return result;
        }
    }

    /**
     * 测试当前 AI 配置是否可用。
     */
    public String test() {
        return chat("Return only: ok");
    }

    private List<Map<String, String>> buildMessages(String prompt) {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", "You are a backend bug analysis agent. Use only the provided evidence and answer in Chinese."));
        messages.add(message("user", prompt));
        return messages;
    }

    private void parseResponse(Map response, AiToolCallResult result) {
        if (response == null || response.get("choices") == null) {
            result.setContent("AI returned empty response.");
            return;
        }
        List choices = (List) response.get("choices");
        if (choices.isEmpty()) {
            result.setContent("AI returned no choices.");
            return;
        }
        Map first = (Map) choices.get(0);
        Map responseMessage = (Map) first.get("message");
        if (responseMessage == null) {
            result.setContent(String.valueOf(first));
            return;
        }
        Object content = responseMessage.get("content");
        result.setContent(content == null ? "" : String.valueOf(content));
        Object toolCallsValue = responseMessage.get("tool_calls");
        if (!(toolCallsValue instanceof List) || ((List) toolCallsValue).isEmpty()) {
            return;
        }
        Map toolCall = (Map) ((List) toolCallsValue).get(0);
        Object functionValue = toolCall.get("function");
        if (!(functionValue instanceof Map)) {
            return;
        }
        Map function = (Map) functionValue;
        Object name = function.get("name");
        Object arguments = function.get("arguments");
        result.setToolName(name == null ? null : String.valueOf(name));
        result.setArgumentsJson(arguments == null ? "{}" : String.valueOf(arguments));
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new HashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 8) + "***";
    }
}
