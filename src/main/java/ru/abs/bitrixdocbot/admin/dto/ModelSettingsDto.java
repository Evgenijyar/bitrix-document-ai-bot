package ru.abs.bitrixdocbot.admin.dto;

import jakarta.validation.constraints.NotNull;
import ru.abs.bitrixdocbot.domain.ModelProvider;

public class ModelSettingsDto {

    @NotNull
    private ModelProvider provider = ModelProvider.OPENAI;
    private String endpoint = "";
    private String modelId = "";
    private String apiKey = "";
    private boolean apiKeyConfigured;

    public ModelProvider getProvider() {
        return provider;
    }

    public void setProvider(ModelProvider provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
    }
}
