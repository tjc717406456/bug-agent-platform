package com.tjc.bugagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口，支持 SSE 流式收取，治慢网关整段干等导致的读超时。
 */
@Service
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 120;
    // 瞬时错误(5xx/429/超时)重试次数与退避基数，扛住网关临时抽风
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 1500;

    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    // 按读超时缓存 RestTemplate；SimpleClientHttpRequestFactory 每次请求新建连接，实例可安全共享
    private final Map<Integer, RestTemplate> restTemplateCache = new ConcurrentHashMap<Integer, RestTemplate>();

    public AiClient(AiConfigService aiConfigService, ObjectMapper objectMapper, AppProperties appProperties) {
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 当前启用的模型是否支持视觉(多模态)。决定要不要把报错截图喂图，避免给纯文本模型发图。
     */
    public boolean currentModelSupportsVision() {
        AiConfig config = aiConfigService.getEnabledConfig();
        return config != null && config.isSupportsVision();
    }

    /** 辅助模型配置：标了 UTILITY 的就用它，没有就退回主模型。 */
    private AiConfig resolveUtilityConfig() {
        AiConfig utility = aiConfigService.getUtilityConfig();
        return utility != null ? utility : aiConfigService.getEnabledConfig();
    }

    /**
     * 发送普通对话请求(主模型)，保留给 AI 配置测试和旧分析流程使用。
     */
    public String chat(String prompt) {
        AiToolCallResult result = chatWithTools(prompt, null);
        return result.getContent();
    }

    /** 辅助模型的单轮对话(接口讲解、多假设侦察等轻活)，省主模型的钱。 */
    public String chatUtility(String prompt) {
        AiToolCallResult result = chatWithMessages(buildMessages(prompt), null, resolveUtilityConfig());
        return result.getContent();
    }

    /**
     * 发送带工具定义的单轮对话请求，内部补全 system + user 消息。
     */
    public AiToolCallResult chatWithTools(String prompt, List<Map<String, Object>> tools) {
        return chatWithMessages(buildMessages(prompt), tools);
    }

    /**
     * 发送多轮对话请求(主模型)，调用方自行维护完整 messages（含 system、历轮 assistant/tool 结果）。
     */
    public AiToolCallResult chatWithMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return chatWithMessages(messages, tools, aiConfigService.getEnabledConfig());
    }

    /** 用辅助模型跑多轮对话(接口讲解等)，省钱。 */
    public AiToolCallResult chatWithMessagesUtility(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return chatWithMessages(messages, tools, resolveUtilityConfig());
    }

    /**
     * 多轮对话核心：用指定的模型配置发送，主/辅模型路由由调用方决定。
     */
    public AiToolCallResult chatWithMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools, AiConfig config) {
        AiToolCallResult result = new AiToolCallResult();
        if (config == null) {
            result.setContent("AI is not configured. The report is generated from local code graph evidence only.");
            return result;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        // 带工具时强制非流式：部分网关（如该中转的 gpt-5.5）流式下会丢 tool_calls，退化成只吐文本，导致 Agent 收不到工具调用
        boolean stream = appProperties.getAi().isStreamEnabled() && (tools == null || tools.isEmpty());
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (stream) {
            body.put("stream", true);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        // 元信息走 info；请求/响应体含源码、SQL、库数据，只在 debug 级别输出，默认不打印
        log.info("AI request url={} model={} stream={} keyPrefix={}", url, config.getModelName(), stream, maskApiKey(config.getApiKey()));
        log.debug("AI request body: {}", body);
        RestTemplate restTemplate = buildRestTemplate(config);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);

        int attempts = MAX_RETRIES + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return stream ? executeStream(restTemplate, url, entity) : executeBlocking(restTemplate, url, entity);
            } catch (Exception exception) {
                // 网关 5xx / 限流 429 / 读超时属于瞬时错误，退避后重试；4xx、响应格式错等不重试
                if (isTransient(exception) && attempt < attempts) {
                    log.warn("AI request transient failure (attempt {}/{}): {}, retrying", attempt, attempts, exception.getMessage());
                    sleep(RETRY_BACKOFF_MS * attempt);
                    continue;
                }
                log.error("AI request failed: {}", exception.getMessage(), exception);
                // 原始错误已进日志，给调用方和前端一句干净提示，不甩网关原文
                result.setContent("AI不可用，请稍后重试或检查 AI 配置");
                return result;
            }
        }
        return result;
    }

    /**
     * 非流式：整段收取后一次性解析。
     */
    private AiToolCallResult executeBlocking(RestTemplate restTemplate, String url, HttpEntity<Map<String, Object>> entity) {
        Map response = restTemplate.postForObject(url, entity, Map.class);
        log.debug("AI response body: {}", response);
        AiToolCallResult result = new AiToolCallResult();
        result.setRawResponse(String.valueOf(response));
        parseResponse(response, result);
        return result;
    }

    /**
     * 流式：逐块读取 SSE，把分片的 content 与 tool_calls 归并成与非流式一致的结果对象。
     */
    private AiToolCallResult executeStream(RestTemplate restTemplate, String url, HttpEntity<Map<String, Object>> entity) {
        return restTemplate.execute(url, HttpMethod.POST, request -> {
            request.getHeaders().addAll(entity.getHeaders());
            objectMapper.writeValue(request.getBody(), entity.getBody());
        }, this::extractStream);
    }

    /**
     * 解析 SSE 响应流：累加 content；tool_calls 按 index 归并（id/name 取首个非空，arguments 逐片拼接）。
     */
    private AiToolCallResult extractStream(ClientHttpResponse response) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        // 按 index 归并工具调用分片，保持模型给出的先后顺序
        Map<Integer, ToolCallChunk> toolChunks = new LinkedHashMap<Integer, ToolCallChunk>();
        boolean sawData = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                sawData = true;
                raw.append(data).append("\n");
                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.size() == 0) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                JsonNode contentNode = delta.path("content");
                if (contentNode.isTextual()) {
                    content.append(contentNode.asText());
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    mergeToolCallChunks(toolChunks, toolCalls);
                }
            }
        }
        // 整段不是 SSE（网关返回 JSON/HTML 错误页时一行 data 都没有），按失败处理，落统一的不可用提示
        if (!sawData) {
            throw new IllegalStateException("AI 响应非流式或为空");
        }

        AiToolCallResult result = new AiToolCallResult();
        result.setRawResponse(raw.toString());
        result.setContent(content.toString());
        List<AiToolCall> calls = new ArrayList<AiToolCall>();
        for (ToolCallChunk chunk : toolChunks.values()) {
            if (chunk.name == null || chunk.name.trim().isEmpty()) {
                continue;
            }
            calls.add(new AiToolCall(chunk.id, chunk.name,
                    chunk.arguments.length() == 0 ? "{}" : chunk.arguments.toString()));
        }
        result.setToolCalls(calls);
        if (!calls.isEmpty()) {
            result.setToolName(calls.get(0).getName());
            result.setArgumentsJson(calls.get(0).getArgumentsJson());
        }
        result.setAssistantMessage(buildAssistantMessage(content.toString(), calls));
        return result;
    }

    private void mergeToolCallChunks(Map<Integer, ToolCallChunk> toolChunks, JsonNode toolCalls) {
        for (JsonNode call : toolCalls) {
            int index = call.path("index").asInt(0);
            ToolCallChunk chunk = toolChunks.get(index);
            if (chunk == null) {
                chunk = new ToolCallChunk();
                toolChunks.put(index, chunk);
            }
            if (call.hasNonNull("id")) {
                chunk.id = call.get("id").asText();
            }
            JsonNode function = call.path("function");
            if (function.hasNonNull("name")) {
                chunk.name = function.get("name").asText();
            }
            JsonNode arguments = function.path("arguments");
            if (arguments.isTextual()) {
                chunk.arguments.append(arguments.asText());
            }
        }
    }

    /**
     * 流式没有现成的原始 assistant message，按 OpenAI 协议自己拼，供多轮回填保证 tool_call 配对。
     */
    private Map<String, Object> buildAssistantMessage(String content, List<AiToolCall> calls) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        // 带工具调用时 content 允许为空，置 null 更贴合多数网关的协议预期
        message.put("content", content == null || content.isEmpty() ? null : content);
        if (!calls.isEmpty()) {
            List<Map<String, Object>> array = new ArrayList<Map<String, Object>>();
            for (AiToolCall call : calls) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", call.getId());
                item.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<String, Object>();
                function.put("name", call.getName());
                function.put("arguments", call.getArgumentsJson());
                item.put("function", function);
                array.add(item);
            }
            message.put("tool_calls", array);
        }
        return message;
    }

    /**
     * 工具调用分片累加器：跨 chunk 拼出完整的一次调用。
     */
    private static final class ToolCallChunk {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 判断是否瞬时错误：网关 5xx、限流 429、连接/读取超时，这些重试有意义。
     */
    private boolean isTransient(Exception exception) {
        if (exception instanceof HttpServerErrorException || exception instanceof ResourceAccessException) {
            return true;
        }
        if (exception instanceof HttpClientErrorException) {
            return ((HttpClientErrorException) exception).getRawStatusCode() == 429;
        }
        // 流式下读超时可能直接以 SocketTimeoutException 抛出（未被 RestTemplate 包成 ResourceAccessException），顺着 cause 链找
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 连通性测试：发一句"你好"，对方回了任何内容就算通。走真实 chat 路径但强制非流式 + 短消息，验地址、密钥、模型一条龙。
     */
    public String test() {
        AiConfig config = aiConfigService.getEnabledConfig();
        if (config == null) {
            return "未配置启用的 AI";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("user", "你好"));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        body.put("max_tokens", 16);
        body.put("stream", false);

        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        // 测试用固定 30 秒超时，"你好"足够快，别按分析那套长超时等
        RestTemplate restTemplate = restTemplateCache.computeIfAbsent(30, this::createRestTemplate);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
        log.info("AI connectivity test url={} model={} keyPrefix={}", url, config.getModelName(), maskApiKey(config.getApiKey()));
        try {
            Map response = restTemplate.postForObject(url, entity, Map.class);
            AiToolCallResult result = new AiToolCallResult();
            parseResponse(response, result);
            String reply = result.getContent();
            // 对方回了任何内容就算通，直接返回 ok
            if (reply == null || reply.trim().isEmpty()) {
                return "连通但模型没回内容，检查模型名是否正确";
            }
            return "ok";
        } catch (HttpClientErrorException exception) {
            int status = exception.getRawStatusCode();
            if (status == 401 || status == 403) {
                return "密钥无效或无权限（HTTP " + status + "）";
            }
            return "请求被拒（HTTP " + status + "）：" + trimMessage(exception.getMessage());
        } catch (Exception exception) {
            return "无法连接 AI 服务：" + trimMessage(rootMessage(exception));
        }
    }

    private String trimMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
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
        // 取本次调用的 token 消耗，做成本追踪
        if (response.get("usage") instanceof Map) {
            Object total = ((Map) response.get("usage")).get("total_tokens");
            if (total instanceof Number) {
                result.setTotalTokens(((Number) total).intValue());
            }
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
