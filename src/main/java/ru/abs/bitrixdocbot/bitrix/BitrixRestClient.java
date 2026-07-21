package ru.abs.bitrixdocbot.bitrix;

import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class BitrixRestClient {

    private final RestClient.Builder restClientBuilder;

    public BitrixRestClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public JsonNode call(String webhookUrl, String method, Object request) {
        validateWebhook(webhookUrl);
        String endpoint = (webhookUrl.endsWith("/") ? webhookUrl : webhookUrl + "/") + method;
        JsonNode response = restClientBuilder.build()
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(JsonNode.class);

        if (response == null) {
            throw new BitrixApiException("Bitrix24 returned an empty response for " + method);
        }
        if (response.path("error").isString()) {
            throw new BitrixApiException(response.path("error").asString() + ": " + response.path("error_description").asString());
        }
        return response;
    }

    public byte[] download(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid download URL");
        }
        byte[] data = restClientBuilder.build().get().uri(url).retrieve().body(byte[].class);
        if (data == null) {
            throw new BitrixApiException("Downloaded file is empty");
        }
        return data;
    }

    private void validateWebhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Bitrix24 incoming webhook URL must start with https://");
        }
    }
}
