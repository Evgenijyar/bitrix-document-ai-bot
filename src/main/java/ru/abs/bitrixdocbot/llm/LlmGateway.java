package ru.abs.bitrixdocbot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import ru.abs.bitrixdocbot.logging.LogSanitizer;

@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final OpenAiLlmClient openAiClient;
    private final GoogleLlmClient googleClient;

    public LlmGateway(OpenAiLlmClient openAiClient, GoogleLlmClient googleClient) {
        this.openAiClient = openAiClient;
        this.googleClient = googleClient;
    }

    public String generate(ModelSettings settings, String systemPrompt, String userPrompt) {
        validate(settings);
        long started = System.nanoTime();
        log.info("LLM GATEWAY -> provider={} endpoint={} modelId={} systemPromptChars={} userPromptChars={}",
            settings.getProvider(),
            LogSanitizer.safeEndpoint(settings.getEndpoint()),
            settings.getModelId(),
            systemPrompt == null ? 0 : systemPrompt.length(),
            userPrompt == null ? 0 : userPrompt.length());
        try {
            String result = switch (settings.getProvider()) {
                case OPENAI -> openAiClient.generate(settings, systemPrompt, userPrompt);
                case GOOGLE -> googleClient.generate(settings, systemPrompt, userPrompt);
            };
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("LLM GATEWAY <- provider={} modelId={} outputChars={} durationMs={}",
                settings.getProvider(), settings.getModelId(), result.length(), durationMs);
            return result;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("LLM GATEWAY !! provider={} endpoint={} modelId={} durationMs={} message={}",
                settings.getProvider(),
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                settings.getModelId(),
                durationMs,
                exception.getMessage(),
                exception);
            throw exception;
        }
    }

    public String test(ModelSettings settings) {
        log.info("LLM TEST started provider={} endpoint={} modelId={}",
            settings == null ? null : settings.getProvider(),
            settings == null ? null : LogSanitizer.safeEndpoint(settings.getEndpoint()),
            settings == null ? null : settings.getModelId());
        String result = generate(settings,
            "Ты проверяешь доступность API. Выполни инструкцию пользователя максимально кратко.",
            "Ответь только словом OK.");
        log.info("LLM TEST completed responseChars={}", result.length());
        return result;
    }

    private void validate(ModelSettings settings) {
        if (settings == null || settings.getProvider() == null) {
            log.error("LLM validation failed: model provider is missing");
            throw new IllegalArgumentException("Model provider is not configured");
        }
        if (!settings.isConfigured()) {
            log.error("LLM validation failed provider={} endpoint={} modelId={} keyConfigured={}",
                settings.getProvider(),
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                settings.getModelId(),
                settings.getApiKey() != null && !settings.getApiKey().isBlank());
            throw new IllegalArgumentException("Endpoint, model ID or API key is not configured");
        }
    }
}
