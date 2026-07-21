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
public class OpenAiLlmClient implements LlmClient {

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

        JsonNode response = restClientBuilder.build()
            .post()
            .uri(settings.getEndpoint())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + settings.getApiKey())
            .body(request)
            .retrieve()
            .body(JsonNode.class);

        String text = extractText(response);
        if (text.isBlank()) {
            throw new IllegalStateException("OpenAI-compatible endpoint returned an empty response");
        }
        return text;
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
