package com.tjc.bugagent.ai;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * AI configuration API.
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
    public ApiResponse<AiConfig> getConfig() {
        return ApiResponse.ok(aiConfigService.getMaskedConfig());
    }

    @PostMapping
    public ApiResponse<Void> save(@Valid @RequestBody SaveAiConfigRequest request) {
        aiConfigService.save(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/test")
    public ApiResponse<String> test() {
        return ApiResponse.ok(aiClient.test());
    }
}

