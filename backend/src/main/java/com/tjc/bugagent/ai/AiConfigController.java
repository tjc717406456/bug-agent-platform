package com.tjc.bugagent.ai;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * AI configuration API.
 *
 * <p>整个 /ai-config 前缀已被 AuthInterceptor 限定为管理员可访问：这里存的是 AI apiKey，
 * 且 activate 是全局互斥开关，一个人切模型会影响所有人。普通用户看当前模型走 /meta/active-model。
 */
@RestController
@RequestMapping("/ai-config")
public class AiConfigController {
    private final AiConfigService aiConfigService;
    private final AiClient aiClient;

    public AiConfigController(AiConfigService aiConfigService, AiClient aiClient) {
        this.aiConfigService = aiConfigService;
        this.aiClient = aiClient;
    }

    @GetMapping
    public ApiResponse<List<AiConfig>> list() {
        return ApiResponse.ok(aiConfigService.list());
    }

    @PostMapping
    public ApiResponse<Void> create(@Valid @RequestBody SaveAiConfigRequest request) {
        aiConfigService.create(request);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody SaveAiConfigRequest request) {
        aiConfigService.update(id, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<Void> activate(@PathVariable Long id) {
        aiConfigService.activate(id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        aiConfigService.delete(id);
        return ApiResponse.ok("ok");
    }

    @PostMapping("/test")
    public ApiResponse<String> test() {
        return ApiResponse.ok(aiClient.test());
    }

    @PostMapping("/test-embedding")
    public ApiResponse<String> testEmbedding() {
        return ApiResponse.ok(aiClient.testEmbedding());
    }
}

