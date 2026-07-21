package ru.abs.bitrixdocbot.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final RestClient.Builder restClientBuilder;

    public OpenAiLlmClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public String generate(ModelSettings settings, String systemPrompt, String userPrompt) {
        boolean chatCompletions = settings.getEndpoint().contains("/chat/completions");
        Object request = chatCompletions
            ? Map.of(
                "model", settings.getModelId(),
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                )
            )
            : Map.of(
                "model", settings.getModelId(),
                "instructions", systemPrompt,
                "input", userPrompt,
                "store", false
            );

        long started = System.nanoTime();
        log.info("OPENAI API -> endpoint={} modelId={} apiMode={} request={}",
            LogSanitizer.safeEndpoint(settings.getEndpoint()),
            settings.getModelId(),
            chatCompletions ? "chat-completions" : "responses",
            LogSanitizer.sanitize(request));

        try {
            JsonNode response = restClientBuilder.build()
                .post()
                .uri(settings.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + settings.getApiKey())
                .body(request)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("OPENAI API HTTP ERROR endpoint={} modelId={} status={} body={}",
                        LogSanitizer.safeEndpoint(settings.getEndpoint()),
                        settings.getModelId(),
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, 2_000));
                    throw new IllegalStateException("OpenAI-compatible endpoint returned HTTP "
                        + httpResponse.getStatusCode() + ": " + LogSanitizer.shortValue(responseBody, 1_000));
                })
                .body(JsonNode.class);

            log.debug("OPENAI API response endpoint={} modelId={} response={}",
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                settings.getModelId(),
                LogSanitizer.sanitizeJson(response));
            String text = extractText(response);
            if (text.isBlank()) {
                throw new IllegalStateException("OpenAI-compatible endpoint returned an empty response");
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("OPENAI API <- endpoint={} modelId={} outputChars={} durationMs={}",
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                settings.getModelId(),
                text.length(),
                durationMs);
            return text;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("OPENAI API !! endpoint={} modelId={} durationMs={} message={}",
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                settings.getModelId(),
                durationMs,
                exception.getMessage(),
                exception);
            throw exception;
        }
    }

    private String extractText(JsonNode root) {
        if (root == null) {
            return "";
        }
        if (root.path("output_text").isString()) {
            return root.path("output_text").asString();
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isString()) {
                return content.asString();
            }
        }

        List<String> texts = new ArrayList<>();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            output.forEach(item -> {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    content.forEach(part -> {
                        if (part.path("text").isString()) {
                            texts.add(part.path("text").asString());
                        }
                        if (part.path("output_text").isString()) {
                            texts.add(part.path("output_text").asString());
                        }
                    });
                }
            });
        }
        return String.join("\n", texts);
    }
}
