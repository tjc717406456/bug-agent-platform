package com.tjc.bugagent.ai;

import javax.validation.constraints.NotBlank;

/**
 * Request for saving global AI configuration.
 */
public class SaveAiConfigRequest {
    @NotBlank
    private String provider;
    @NotBlank
    private String baseUrl;
    @NotBlank
    private String modelName;
    @NotBlank
    private String apiKey;
    private Integer timeoutSeconds = 60;
    private boolean enabled = true;
    // 该模型是否支持视觉(多模态)，支持才会把报错截图喂图识读
    private boolean supportsVision = false;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(boolean supportsVision) {
        this.supportsVision = supportsVision;
    }
}

