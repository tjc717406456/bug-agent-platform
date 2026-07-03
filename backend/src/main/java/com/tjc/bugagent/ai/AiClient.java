package com.tjc.bugagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.SocketException;
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
    // 按读超时缓存 RestTemplate；底层共用一个连接池，复用 TCP/TLS 连接省重握手
    private final Map<Integer, RestTemplate> restTemplateCache = new ConcurrentHashMap<Integer, RestTemplate>();
    // 全局共享连接池：并发分析链、每轮 LLM 请求都打同一网关，池化后 keep-alive 连接反复复用
    private final PoolingHttpClientConnectionManager connectionManager = buildConnectionManager();

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
        return chatUtilityResult(prompt).getContent();
    }

    /** chatUtility 的带用量版本：调用方需要累计 token 消耗时用这个。 */
    public AiToolCallResult chatUtilityResult(String prompt) {
        return chatWithMessages(buildMessages(prompt), null, resolveUtilityConfig());
    }

    /**
     * 纯文字流式生成（主模型）：不带工具、强制 stream。网关流式丢的是 tool_calls，纯 content 分块不受影响，
     * 且分块回传不会被网关读超时掐断。onPartial 收到的是**累计全文快照**（重试自动清零重来，调用方无需自己拼）。
     */
    public AiToolCallResult chatProseStream(List<Map<String, Object>> messages, java.util.function.Consumer<String> onPartial) {
        return chatProseStream(messages, aiConfigService.getEnabledConfig(), onPartial);
    }

    /** 纯文字流式生成（辅助模型），接口讲解收口用。 */
    public AiToolCallResult chatProseStreamUtility(List<Map<String, Object>> messages, java.util.function.Consumer<String> onPartial) {
        return chatProseStream(messages, resolveUtilityConfig(), onPartial);
    }

    private AiToolCallResult chatProseStream(List<Map<String, Object>> messages, AiConfig config,
                                             java.util.function.Consumer<String> onPartial) {
        AiToolCallResult failedResult = new AiToolCallResult();
        if (config == null) {
            failedResult.setContent("AI is not configured. The report is generated from local code graph evidence only.");
            failedResult.setFailed(true);
            return failedResult;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("stream", true);
        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        RestTemplate restTemplate = buildRestTemplate(config);
        int attempts = MAX_RETRIES + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            log.info("AI request url={} model={} stream=true(prose) keyPrefix={}", url, config.getModelName(), maskApiKey(config.getApiKey()));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
            // 每次尝试独立累计，重试时快照从零重来，不会把上次半截内容拼进去
            StringBuilder accumulated = new StringBuilder();
            try {
                long callStartMs = System.currentTimeMillis();
                AiToolCallResult result = executeStream(restTemplate, url, entity, delta -> {
                    accumulated.append(delta);
                    if (onPartial != null) {
                        onPartial.accept(accumulated.toString());
                    }
                });
                log.info("AI response model={} 耗时={}ms tokens={} (prose-stream)", config.getModelName(),
                        System.currentTimeMillis() - callStartMs, result.getTotalTokens());
                return result;
            } catch (Exception exception) {
                if (isTransient(exception) && attempt < attempts) {
                    log.warn("AI prose-stream transient failure (attempt {}/{}): {}, retrying", attempt, attempts, exception.getMessage());
                    sleep(RETRY_BACKOFF_MS * attempt);
                    continue;
                }
                log.error("AI prose-stream failed: {}", exception.getMessage(), exception);
                failedResult.setContent("AI不可用，请稍后重试或检查 AI 配置");
                failedResult.setFailed(true);
                return failedResult;
            }
        }
        return failedResult;
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

    /**
     * 主模型多轮对话并强制本轮必发工具调用(tool_choice=required)：探查/收尾轮用，从源头堵"只给文字计划不调工具"。
     * 受 app.ai.tool-choice-required 开关控制；网关不认 required 时由核心方法自动降级为 auto 重试。
     */
    public AiToolCallResult chatWithMessagesRequired(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        String toolChoice = appProperties.getAi().isToolChoiceRequired() ? "required" : "auto";
        return chatWithMessages(messages, tools, aiConfigService.getEnabledConfig(), toolChoice);
    }

    /** 用辅助模型跑多轮对话(接口讲解等)，省钱。 */
    public AiToolCallResult chatWithMessagesUtility(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return chatWithMessages(messages, tools, resolveUtilityConfig());
    }

    /**
     * 多轮对话核心：用指定的模型配置发送，主/辅模型路由由调用方决定。tool_choice 默认 auto。
     */
    public AiToolCallResult chatWithMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools, AiConfig config) {
        return chatWithMessages(messages, tools, config, "auto");
    }

    /**
     * 多轮对话核心：toolChoice 控制是否强制调工具（auto/required）。带工具且 required 时若网关返 400，自动降级 auto 重试一次。
     */
    public AiToolCallResult chatWithMessages(List<Map<String, Object>> messages, List<Map<String, Object>> tools, AiConfig config, String toolChoice) {
        AiToolCallResult result = new AiToolCallResult();
        if (config == null) {
            result.setContent("AI is not configured. The report is generated from local code graph evidence only.");
            result.setFailed(true);
            return result;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        boolean streamEnabled = appProperties.getAi().isStreamEnabled();
        // 带工具时默认非流式：部分网关流式下会丢 tool_calls。但非流式长请求易被网关掐连接（reset/EOF），
        // 故保留"断连时自动降级为流式重试"——流式分块返回不易被网关空闲超时掐，且 SSE 解析照样能拼出 tool_calls。
        boolean hasTools = tools != null && !tools.isEmpty();
        boolean useStream = streamEnabled && !hasTools;
        boolean canFallbackToStream = streamEnabled && hasTools;
        // required 逼模型本轮必发 tool_calls；部分网关不认，catch 里检测 400 后降级为 auto 重试
        String effectiveToolChoice = (toolChoice == null || toolChoice.trim().isEmpty()) ? "auto" : toolChoice;

        Map<String, Object> baseBody = new HashMap<String, Object>();
        baseBody.put("model", config.getModelName());
        baseBody.put("messages", messages);
        baseBody.put("temperature", 0.2);
        if (hasTools) {
            baseBody.put("tools", tools);
            baseBody.put("tool_choice", effectiveToolChoice);
        }

        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        RestTemplate restTemplate = buildRestTemplate(config);

        int attempts = MAX_RETRIES + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Map<String, Object> body = new HashMap<String, Object>(baseBody);
            if (useStream) {
                body.put("stream", true);
            }
            // 元信息走 info；请求/响应体含源码、SQL、库数据，只在 debug 级别输出，默认不打印
            log.info("AI request url={} model={} stream={} keyPrefix={}", url, config.getModelName(), useStream, maskApiKey(config.getApiKey()));
            log.debug("AI request body: {}", body);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
            try {
                long callStartMs = System.currentTimeMillis();
                AiToolCallResult callResult = useStream ? executeStream(restTemplate, url, entity) : executeBlocking(restTemplate, url, entity);
                // 与上面的 request 日志配对：长生成期间的"沉默"到这行落地，耗时/用量一目了然
                log.info("AI response model={} 耗时={}ms tokens={} toolCalls={}", config.getModelName(),
                        System.currentTimeMillis() - callStartMs, callResult.getTotalTokens(), callResult.getToolCalls().size());
                return callResult;
            } catch (Exception exception) {
                // required 不被网关支持时通常返 400：降级 auto 立即重试，别硬断（仍保留 nudge 作二级兜底）
                if (hasTools && "required".equals(effectiveToolChoice) && isBadRequest(exception) && attempt < attempts) {
                    effectiveToolChoice = "auto";
                    baseBody.put("tool_choice", "auto");
                    log.warn("网关疑似不支持 tool_choice=required，降级为 auto 重试 (attempt {}/{}): {}", attempt, attempts, exception.getMessage());
                    continue;
                }
                // 网关 5xx / 限流 429 / 读超时属于瞬时错误，退避后重试；4xx、响应格式错等不重试
                if (isTransient(exception) && attempt < attempts) {
                    // 非流式被网关中途掐断（reset/EOF）时，下次重试切流式：分块返回扛得住网关的空闲超时
                    if (!useStream && canFallbackToStream && isConnectionDrop(exception)) {
                        useStream = true;
                        log.warn("非流式连接被网关中断，重试改用流式 (attempt {}/{}): {}", attempt, attempts, exception.getMessage());
                    } else {
                        log.warn("AI request transient failure (attempt {}/{}): {}, retrying", attempt, attempts, exception.getMessage());
                    }
                    sleep(RETRY_BACKOFF_MS * attempt);
                    continue;
                }
                log.error("AI request failed: {}", exception.getMessage(), exception);
                // 原始错误已进日志，给调用方和前端一句干净提示，不甩网关原文
                result.setContent("AI不可用，请稍后重试或检查 AI 配置");
                result.setFailed(true);
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
        return executeStream(restTemplate, url, entity, null);
    }

    /** onDelta 非空时每收到一段 content 增量回调一次，供收口报告边生成边推给前端。 */
    private AiToolCallResult executeStream(RestTemplate restTemplate, String url, HttpEntity<Map<String, Object>> entity,
                                           java.util.function.Consumer<String> onDelta) {
        return restTemplate.execute(url, HttpMethod.POST, request -> {
            request.getHeaders().addAll(entity.getHeaders());
            objectMapper.writeValue(request.getBody(), entity.getBody());
        }, response -> extractStream(response, onDelta));
    }

    /**
     * 解析 SSE 响应流：累加 content；tool_calls 按 index 归并（id/name 取首个非空，arguments 逐片拼接）。
     */
    private AiToolCallResult extractStream(ClientHttpResponse response, java.util.function.Consumer<String> onDelta) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        // 按 index 归并工具调用分片，保持模型给出的先后顺序
        Map<Integer, ToolCallChunk> toolChunks = new LinkedHashMap<Integer, ToolCallChunk>();
        boolean sawData = false;
        // 部分网关会在末尾 chunk 带 usage，抓到就记，抓不到 token 计 0（流式协议没强制回用量）
        int totalTokens = 0;

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
                JsonNode usage = chunk.path("usage");
                if (usage.has("total_tokens")) {
                    totalTokens = usage.path("total_tokens").asInt(0);
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.size() == 0) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                JsonNode contentNode = delta.path("content");
                if (contentNode.isTextual()) {
                    content.append(contentNode.asText());
                    if (onDelta != null && !contentNode.asText().isEmpty()) {
                        onDelta.accept(contentNode.asText());
                    }
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
        result.setTotalTokens(totalTokens);
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

    /** 是否 400 Bad Request：用于探测网关不认 tool_choice=required，从而降级为 auto。 */
    private boolean isBadRequest(Exception exception) {
        return exception instanceof HttpClientErrorException
                && ((HttpClientErrorException) exception).getRawStatusCode() == 400;
    }

    /**
     * 是否网关把连接掐了（Connection reset / Unexpected end of file / 读超时）——这类切流式重试有意义；
     * 5xx/429 是服务端已响应的错误，流式救不了，不触发降级。
     */
    private boolean isConnectionDrop(Exception exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof SocketTimeoutException || cause instanceof java.io.EOFException) {
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
     * 取文本的语义向量，供相似案例的语义召回用。复用当前启用模型的 base_url/key，模型名走 app.ai.embedding-model。
     * 未配 embedding 模型、无可用 AI、或调用失败一律返回 null，调用方据此退回纯词法匹配，不影响主流程。
     */
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        // 用 AI 配置里 role=EMBEDDING 且启用的那条；没有就是没开语义召回，返回 null
        AiConfig config = aiConfigService.getEmbeddingConfig();
        if (config == null) {
            return null;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        // 截断超长文本，控住 token 成本；案例摘要几百字足够表达语义
        body.put("input", text.length() > 4000 ? text.substring(0, 4000) : text);

        String url = config.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
        int timeout = config.getTimeoutSeconds() == null || config.getTimeoutSeconds() <= 0 ? 30 : config.getTimeoutSeconds();
        RestTemplate restTemplate = restTemplateCache.computeIfAbsent(timeout, this::createRestTemplate);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
        try {
            Map response = restTemplate.postForObject(url, entity, Map.class);
            return parseEmbedding(response);
        } catch (Exception exception) {
            log.warn("embedding 调用失败 model={} url={}: {}", config.getModelName(), url, exception.getMessage());
            return null;
        }
    }

    /** 解析 OpenAI 兼容 /embeddings 响应的 data[0].embedding，格式不符返回 null。 */
    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(Map response) {
        if (response == null || !(response.get("data") instanceof List)) {
            return null;
        }
        List data = (List) response.get("data");
        if (data.isEmpty() || !(data.get(0) instanceof Map)) {
            return null;
        }
        Object vector = ((Map) data.get(0)).get("embedding");
        if (!(vector instanceof List)) {
            return null;
        }
        List<Object> values = (List<Object>) vector;
        float[] result = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index) instanceof Number ? ((Number) values.get(index)).floatValue() : 0f;
        }
        return result;
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

    /**
     * embedding 连通性测试：拿启用的 EMBEDDING 配置打一炮 /embeddings，验地址、密钥、模型是否真支持向量。
     */
    public String testEmbedding() {
        AiConfig config = aiConfigService.getEmbeddingConfig();
        if (config == null) {
            return "未配置启用的 embedding 模型（新增配置时角色选\"向量\"并启用）";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", config.getModelName());
        body.put("input", "连通性测试");

        String url = config.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
        RestTemplate restTemplate = restTemplateCache.computeIfAbsent(30, this::createRestTemplate);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
        log.info("embedding 连通性测试 url={} model={} keyPrefix={}", url, config.getModelName(), maskApiKey(config.getApiKey()));
        try {
            Map response = restTemplate.postForObject(url, entity, Map.class);
            float[] vector = parseEmbedding(response);
            if (vector == null || vector.length == 0) {
                return "连通但未返回向量，确认填的是 embedding 模型（如 bge-m3/text-embedding-3-small），不是聊天模型";
            }
            return "ok（向量维度 " + vector.length + "）";
        } catch (HttpClientErrorException exception) {
            int status = exception.getRawStatusCode();
            if (status == 401 || status == 403) {
                return "密钥无效或无权限（HTTP " + status + "）";
            }
            if (status == 404) {
                return "该网关不提供 /embeddings 接口（HTTP 404），换个支持 embedding 的源";
            }
            return "请求被拒（HTTP " + status + "）：" + trimMessage(exception.getMessage());
        } catch (Exception exception) {
            return "无法连接 embedding 服务：" + trimMessage(rootMessage(exception));
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

    /**
     * 全局连接池：AI 请求全打同一网关，池化后 keep-alive 连接反复复用，免掉每轮的 TCP+TLS 握手。
     * validateAfterInactivity 保证复用前校验空闲连接，避开网关早已关掉的死连接（NoHttpResponseException）。
     */
    private static PoolingHttpClientConnectionManager buildConnectionManager() {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(100);
        manager.setDefaultMaxPerRoute(50);
        manager.setValidateAfterInactivity(5000);
        return manager;
    }

    /**
     * 每种读超时建一个 RestTemplate，但共用同一连接池（setConnectionManagerShared=true，close 不销毁池）。
     * 读超时走 RequestConfig 的 socketTimeout，与旧的 setReadTimeout 语义一致。
     */
    private RestTemplate createRestTemplate(int readTimeoutSeconds) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(readTimeoutSeconds * 1000)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
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
