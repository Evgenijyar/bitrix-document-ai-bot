package ru.abs.bitrixdocbot.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.domain.ModelSettings;

@Component
public class GoogleLlmClient implements LlmClient {

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

        JsonNode response = restClientBuilder.build()
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("x-goog-api-key", settings.getApiKey())
            .body(request)
            .retrieve()
            .body(JsonNode.class);

        String text = extractText(response);
        if (text.isBlank()) {
            throw new IllegalStateException("Google endpoint returned an empty response");
        }
        return text;
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
