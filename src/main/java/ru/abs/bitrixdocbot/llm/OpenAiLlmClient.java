package ru.abs.bitrixdocbot.llm;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient.Builder restClientBuilder;

    public OpenAiLlmClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public String generate(ModelSettings settings, String systemPrompt, String userPrompt) {
        ResolvedEndpoint resolved = resolveEndpoint(settings.getEndpoint());
        Map<String, Object> request = resolved.mode() == ApiMode.CHAT_COMPLETIONS
            ? buildChatCompletionsRequest(settings.getModelId(), systemPrompt, userPrompt, true)
            : buildResponsesRequest(settings.getModelId(), systemPrompt, userPrompt);

        long started = System.nanoTime();
        log.info("OPENAI API -> configuredEndpoint={} resolvedEndpoint={} modelId={} apiMode={}",
            LogSanitizer.safeEndpoint(settings.getEndpoint()),
            LogSanitizer.safeEndpoint(resolved.url()),
            settings.getModelId(),
            resolved.mode().logValue);
        log.warn("==================== EXACT LLM HTTP JSON BODY START ====================\n{}\n==================== EXACT LLM HTTP JSON BODY END ======================", toJson(request));

        try {
            JsonNode response;
            try {
                response = execute(settings, resolved, request);
            } catch (OpenAiHttpException exception) {
                if (resolved.mode() == ApiMode.CHAT_COMPLETIONS && shouldRetryWithoutReasoning(exception)) {
                    log.warn("OPENAI API provider rejected reasoning.enabled; retrying once without reasoning "
                            + "resolvedEndpoint={} modelId={} status={}",
                        LogSanitizer.safeEndpoint(resolved.url()),
                        settings.getModelId(),
                        exception.statusCode);
                    request = buildChatCompletionsRequest(settings.getModelId(), systemPrompt, userPrompt, false);
                    response = execute(settings, resolved, request);
                } else {
                    throw exception;
                }
            }

            log.debug("OPENAI API response resolvedEndpoint={} modelId={} response={}",
                LogSanitizer.safeEndpoint(resolved.url()),
                settings.getModelId(),
                LogSanitizer.sanitizeJson(response));

            String text = extractText(response);
            if (text.isBlank()) {
                throw new IllegalStateException("OpenAI-compatible endpoint returned an empty response");
            }

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("OPENAI API <- resolvedEndpoint={} modelId={} outputChars={} durationMs={}",
                LogSanitizer.safeEndpoint(resolved.url()),
                settings.getModelId(),
                text.length(),
                durationMs);
            return text;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("OPENAI API !! configuredEndpoint={} resolvedEndpoint={} modelId={} durationMs={} message={}",
                LogSanitizer.safeEndpoint(settings.getEndpoint()),
                LogSanitizer.safeEndpoint(resolved.url()),
                settings.getModelId(),
                durationMs,
                exception.getMessage(),
                exception);
            throw exception;
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private JsonNode execute(
        ModelSettings settings,
        ResolvedEndpoint resolved,
        Map<String, Object> request
    ) {
        return restClientBuilder.build()
            .post()
            .uri(resolved.url())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + settings.getApiKey())
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                log.error("OPENAI API HTTP ERROR configuredEndpoint={} resolvedEndpoint={} modelId={} "
                        + "status={} body={}",
                    LogSanitizer.safeEndpoint(settings.getEndpoint()),
                    LogSanitizer.safeEndpoint(resolved.url()),
                    settings.getModelId(),
                    httpResponse.getStatusCode(),
                    LogSanitizer.shortValue(responseBody, 2_000));

                String hint = httpResponse.getStatusCode().value() == 404
                    ? " Проверьте endpoint. Для OpenAI-совместимого Chat Completions нужен адрес "
                        + "вида https://host/v1/chat/completions; базовый адрес https://host/v1 "
                        + "приложение дополняет автоматически."
                    : "";

                throw new OpenAiHttpException(
                    httpResponse.getStatusCode(),
                    responseBody,
                    "OpenAI-compatible endpoint returned HTTP " + httpResponse.getStatusCode()
                        + ": " + LogSanitizer.shortValue(responseBody, 1_000) + hint
                );
            })
            .body(JsonNode.class);
    }

    ResolvedEndpoint resolveEndpoint(String configuredEndpoint) {
        if (configuredEndpoint == null || configuredEndpoint.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible endpoint is not configured");
        }

        String normalized = configuredEndpoint.trim();
        while (normalized.endsWith("/") && normalized.length() > "https://x".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid OpenAI-compatible endpoint URL", exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null
            || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("OpenAI-compatible endpoint must be an absolute HTTP(S) URL");
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/responses")) {
            return new ResolvedEndpoint(normalized, ApiMode.RESPONSES);
        }
        if (lower.endsWith("/chat/completions")) {
            return new ResolvedEndpoint(normalized, ApiMode.CHAT_COMPLETIONS);
        }
        if (lower.endsWith("/v1")) {
            return new ResolvedEndpoint(normalized + "/chat/completions", ApiMode.CHAT_COMPLETIONS);
        }

        // An explicitly supplied non-standard URL is treated as a Chat Completions endpoint.
        // Responses mode is selected only when the URL explicitly ends with /responses.
        return new ResolvedEndpoint(normalized, ApiMode.CHAT_COMPLETIONS);
    }

    Map<String, Object> buildChatCompletionsRequest(
        String modelId,
        String systemPrompt,
        String userPrompt,
        boolean reasoningEnabled
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", modelId);
        request.put("messages", messages);
        if (reasoningEnabled) {
            request.put("reasoning", Map.of("enabled", true));
        }
        return request;
    }

    private Map<String, Object> buildResponsesRequest(String modelId, String systemPrompt, String userPrompt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", modelId);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            request.put("instructions", systemPrompt);
        }
        request.put("input", userPrompt == null ? "" : userPrompt);
        request.put("store", false);
        return request;
    }

    private boolean shouldRetryWithoutReasoning(OpenAiHttpException exception) {
        if (exception.statusCode.value() != 400 && exception.statusCode.value() != 422) {
            return false;
        }
        String body = exception.responseBody.toLowerCase(Locale.ROOT);
        return body.contains("reasoning")
            && (body.contains("unknown")
                || body.contains("unsupported")
                || body.contains("unrecognized")
                || body.contains("not allowed")
                || body.contains("extra")
                || body.contains("invalid"));
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
            JsonNode firstChoice = choices.get(0);
            JsonNode content = firstChoice.path("message").path("content");
            String chatText = extractContent(content);
            if (!chatText.isBlank()) {
                return chatText;
            }
            if (firstChoice.path("text").isString()) {
                return firstChoice.path("text").asString();
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

    private String extractContent(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        if (!content.isArray()) {
            return "";
        }

        List<String> texts = new ArrayList<>();
        content.forEach(part -> {
            if (part.isString()) {
                texts.add(part.asString());
            } else if (part.path("text").isString()) {
                texts.add(part.path("text").asString());
            } else if (part.path("content").isString()) {
                texts.add(part.path("content").asString());
            }
        });
        return String.join("\n", texts);
    }

    enum ApiMode {
        CHAT_COMPLETIONS("chat-completions"),
        RESPONSES("responses");

        private final String logValue;

        ApiMode(String logValue) {
            this.logValue = logValue;
        }
    }

    record ResolvedEndpoint(String url, ApiMode mode) {
    }

    private static final class OpenAiHttpException extends IllegalStateException {
        private final HttpStatusCode statusCode;
        private final String responseBody;

        private OpenAiHttpException(HttpStatusCode statusCode, String responseBody, String message) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody == null ? "" : responseBody;
        }
    }
}
