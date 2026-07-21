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
public class GoogleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleLlmClient.class);

    private final RestClient.Builder restClientBuilder;

    public GoogleLlmClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public String generate(ModelSettings settings, String systemPrompt, String userPrompt) {
        String endpoint = resolveEndpoint(settings.getEndpoint(), settings.getModelId());
        Map<String, Object> request = Map.of(
            "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
            "contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userPrompt))
            ))
        );

        long started = System.nanoTime();
        log.info("GOOGLE API -> endpoint={} modelId={} request={}",
            LogSanitizer.safeEndpoint(endpoint),
            settings.getModelId(),
            LogSanitizer.sanitize(request));

        try {
            JsonNode response = restClientBuilder.build()
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", settings.getApiKey())
                .body(request)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("GOOGLE API HTTP ERROR endpoint={} modelId={} status={} body={}",
                        LogSanitizer.safeEndpoint(endpoint),
                        settings.getModelId(),
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, 2_000));
                    throw new IllegalStateException("Google endpoint returned HTTP "
                        + httpResponse.getStatusCode() + ": " + LogSanitizer.shortValue(responseBody, 1_000));
                })
                .body(JsonNode.class);

            log.debug("GOOGLE API response endpoint={} modelId={} response={}",
                LogSanitizer.safeEndpoint(endpoint),
                settings.getModelId(),
                LogSanitizer.sanitizeJson(response));
            String text = extractText(response);
            if (text.isBlank()) {
                throw new IllegalStateException("Google endpoint returned an empty response");
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("GOOGLE API <- endpoint={} modelId={} outputChars={} durationMs={}",
                LogSanitizer.safeEndpoint(endpoint), settings.getModelId(), text.length(), durationMs);
            return text;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("GOOGLE API !! endpoint={} modelId={} durationMs={} message={}",
                LogSanitizer.safeEndpoint(endpoint),
                settings.getModelId(),
                durationMs,
                exception.getMessage(),
                exception);
            throw exception;
        }
    }

    String resolveEndpoint(String endpoint, String modelId) {
        if (endpoint.contains("{model}")) {
            return endpoint.replace("{model}", modelId);
        }
        if (endpoint.endsWith(":generateContent")) {
            return endpoint;
        }
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base + "/models/" + modelId + ":generateContent";
    }

    private String extractText(JsonNode root) {
        if (root == null) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                parts.forEach(part -> {
                    if (part.path("text").isString()) {
                        texts.add(part.path("text").asString());
                    }
                });
            }
        }
        return String.join("\n", texts);
    }
}
