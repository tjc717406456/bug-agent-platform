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
}

