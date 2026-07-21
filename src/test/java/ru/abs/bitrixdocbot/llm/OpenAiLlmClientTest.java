package ru.abs.bitrixdocbot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiLlmClientTest {

    private final OpenAiLlmClient client = new OpenAiLlmClient(RestClient.builder());

    @Test
    void appendsChatCompletionsToV1BaseEndpoint() {
        OpenAiLlmClient.ResolvedEndpoint resolved = client.resolveEndpoint("https://api.tokenator.top/v1");

        assertThat(resolved.url()).isEqualTo("https://api.tokenator.top/v1/chat/completions");
        assertThat(resolved.mode()).isEqualTo(OpenAiLlmClient.ApiMode.CHAT_COMPLETIONS);
    }

    @Test
    void removesTrailingSlashBeforeAppendingChatCompletions() {
        OpenAiLlmClient.ResolvedEndpoint resolved = client.resolveEndpoint("https://api.tokenator.top/v1/");

        assertThat(resolved.url()).isEqualTo("https://api.tokenator.top/v1/chat/completions");
        assertThat(resolved.mode()).isEqualTo(OpenAiLlmClient.ApiMode.CHAT_COMPLETIONS);
    }

    @Test
    void preservesExplicitChatCompletionsEndpoint() {
        OpenAiLlmClient.ResolvedEndpoint resolved = client.resolveEndpoint(
            "https://api.tokenator.top/v1/chat/completions"
        );

        assertThat(resolved.url()).isEqualTo("https://api.tokenator.top/v1/chat/completions");
        assertThat(resolved.mode()).isEqualTo(OpenAiLlmClient.ApiMode.CHAT_COMPLETIONS);
    }

    @Test
    void usesResponsesOnlyWhenExplicitlyConfigured() {
        OpenAiLlmClient.ResolvedEndpoint resolved = client.resolveEndpoint("https://api.openai.com/v1/responses");

        assertThat(resolved.url()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(resolved.mode()).isEqualTo(OpenAiLlmClient.ApiMode.RESPONSES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsTokenatorCompatibleChatRequestWithReasoningEnabled() {
        Map<String, Object> request = client.buildChatCompletionsRequest(
            "gpt-5.5",
            "System prompt",
            "User prompt",
            true
        );

        assertThat(request.get("model")).isEqualTo("gpt-5.5");
        assertThat(request.get("reasoning")).isEqualTo(Map.of("enabled", true));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        assertThat(messages).containsExactly(
            Map.of("role", "system", "content", "System prompt"),
            Map.of("role", "user", "content", "User prompt")
        );
    }
}
