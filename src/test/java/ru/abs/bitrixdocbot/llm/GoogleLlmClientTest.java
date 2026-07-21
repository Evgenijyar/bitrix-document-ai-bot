package ru.abs.bitrixdocbot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GoogleLlmClientTest {

    private final GoogleLlmClient client = new GoogleLlmClient(RestClient.builder());

    @Test
    void replacesModelPlaceholder() {
        String resolved = client.resolveEndpoint(
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            "some-model"
        );
        assertThat(resolved).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/some-model:generateContent"
        );
    }
}
