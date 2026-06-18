package com.tjc.bugagent.ai;

import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

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
    public ApiResponse<List<AiConfig>> list() {
        return ApiResponse.ok(aiConfigService.list());
    }

    @PostMapping
    public ApiResponse<Void> create(@Valid @RequestBody SaveAiConfigRequest request) {
        aiConfigService.create(request);
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
}

