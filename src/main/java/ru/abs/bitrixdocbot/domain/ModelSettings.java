package ru.abs.bitrixdocbot.domain;

public class ModelSettings {

    private ModelProvider provider = ModelProvider.OPENAI;
    private String endpoint = "";
    private String modelId = "";
    private String apiKey = "";

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

    public boolean isConfigured() {
        return provider != null
            && endpoint != null && !endpoint.isBlank()
            && modelId != null && !modelId.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }
}
