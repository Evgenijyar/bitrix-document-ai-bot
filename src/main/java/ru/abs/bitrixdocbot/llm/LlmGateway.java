package ru.abs.bitrixdocbot.llm;

import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.domain.ModelProvider;
import ru.abs.bitrixdocbot.domain.ModelSettings;

@Service
public class LlmGateway {

    private final OpenAiLlmClient openAiClient;
    private final GoogleLlmClient googleClient;

    public LlmGateway(OpenAiLlmClient openAiClient, GoogleLlmClient googleClient) {
        this.openAiClient = openAiClient;
        this.googleClient = googleClient;
    }

    public String generate(ModelSettings settings, String systemPrompt, String userPrompt) {
        validate(settings);
        return switch (settings.getProvider()) {
            case OPENAI -> openAiClient.generate(settings, systemPrompt, userPrompt);
            case GOOGLE -> googleClient.generate(settings, systemPrompt, userPrompt);
        };
    }

    public String test(ModelSettings settings) {
        return generate(settings,
            "Ты проверяешь доступность API. Выполни инструкцию пользователя максимально кратко.",
            "Ответь только словом OK.");
    }

    private void validate(ModelSettings settings) {
        if (settings == null || settings.getProvider() == null) {
            throw new IllegalArgumentException("Model provider is not configured");
        }
        if (!settings.isConfigured()) {
            throw new IllegalArgumentException("Endpoint, model ID or API key is not configured");
        }
    }
}
