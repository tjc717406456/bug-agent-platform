package com.tjc.bugagent.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口。
 */
@Service
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;

    private final AiConfigService aiConfigService;
    // 按读超时缓存 RestTemplate；SimpleClientHttpRequestFactory 每次请求新建连接，实例可安全共享
    private final Map<Integer, RestTemplate> restTemplateCache = new ConcurrentHashMap<Integer, RestTemplate>();

    public AiClient(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    /**
     * 发送普通对话请求，保留给 AI 配置测试和旧分析流程使用。
     */
    public String chat(String prompt) {
        AiToolCallResult result = chatWithTools(prompt, null);
        return result.getContent();
    }

    /**
     * 发送带工具定义的单轮对话请求，内部补全 system + user 消息。
     */
    public AiToolCallResult chatWithTools(String prompt, List<Map<String, Object>> tools) {
        return chatWithMessages(buildMessages(prompt), tools);
    }

    /**
     * 发送多轮对话请求，调用方自行维护完整 messages（含 system、历轮 assistant/tool 结果）。
     */
    public AiToolCallResult chatWithMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
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
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        try {
            String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            // 元信息走 info；请求/响应体含源码、SQL、库数据，只在 debug 级别输出，默认不打印
            log.info("AI request url={} model={} keyPrefix={}", url, config.getModelName(), maskApiKey(config.getApiKey()));
            log.debug("AI request body: {}", body);

            // 按读超时取缓存的 RestTemplate，避免每轮新建
            RestTemplate restTemplate = buildRestTemplate(config);
            Map response = restTemplate.postForObject(url, new HttpEntity<Map<String, Object>>(body, headers), Map.class);

            log.debug("AI response body: {}", response);

            result.setRawResponse(String.valueOf(response));
            parseResponse(response, result);
            return result;
        } catch (Exception exception) {
            log.error("AI request failed: {}", exception.getMessage(), exception);
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

    private List<Map<String, Object>> buildMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", "You are a backend bug analysis agent. Use only the provided evidence and answer in Chinese."));
        messages.add(message("user", prompt));
        return messages;
    }

    @SuppressWarnings("unchecked")
    private void parseResponse(Map response, AiToolCallResult result) {
        if (response == null || !(response.get("choices") instanceof List)) {
            result.setContent("AI returned empty response.");
            return;
        }
        List choices = (List) response.get("choices");
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            result.setContent("AI returned no choices.");
            return;
        }
        Map first = (Map) choices.get(0);
        if (!(first.get("message") instanceof Map)) {
            result.setContent(String.valueOf(first));
            return;
        }
        Map<String, Object> responseMessage = (Map<String, Object>) first.get("message");
        // 留存原始 assistant message，供多轮对话原样回填
        result.setAssistantMessage(responseMessage);
        Object content = responseMessage.get("content");
        result.setContent(content == null ? "" : String.valueOf(content));
        Object toolCallsValue = responseMessage.get("tool_calls");
        if (!(toolCallsValue instanceof List) || ((List) toolCallsValue).isEmpty()) {
            return;
        }
        // 解析本轮全部 tool_calls，让上层一轮内并行执行，减少串行 LLM 往返
        List<AiToolCall> toolCalls = new ArrayList<AiToolCall>();
        for (Object item : (List) toolCallsValue) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map toolCall = (Map) item;
            Object functionValue = toolCall.get("function");
            if (!(functionValue instanceof Map)) {
                continue;
            }
            Map function = (Map) functionValue;
            Object name = function.get("name");
            if (name == null || String.valueOf(name).trim().isEmpty()) {
                continue;
            }
            Object arguments = function.get("arguments");
            Object id = toolCall.get("id");
            toolCalls.add(new AiToolCall(
                    id == null ? null : String.valueOf(id),
                    String.valueOf(name),
                    arguments == null ? "{}" : String.valueOf(arguments)));
        }
        result.setToolCalls(toolCalls);
        if (!toolCalls.isEmpty()) {
            // 兼容只读首个调用的旧逻辑
            result.setToolName(toolCalls.get(0).getName());
            result.setArgumentsJson(toolCalls.get(0).getArgumentsJson());
        }
    }

    private RestTemplate buildRestTemplate(AiConfig config) {
        int readTimeoutSeconds = config.getTimeoutSeconds() == null || config.getTimeoutSeconds() <= 0
                ? DEFAULT_READ_TIMEOUT_SECONDS : config.getTimeoutSeconds();
        return restTemplateCache.computeIfAbsent(readTimeoutSeconds, this::createRestTemplate);
    }

    private RestTemplate createRestTemplate(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new HashMap<String, Object>();
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
