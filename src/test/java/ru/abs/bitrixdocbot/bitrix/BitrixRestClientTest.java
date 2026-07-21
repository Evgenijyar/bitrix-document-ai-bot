package ru.abs.bitrixdocbot.bitrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

class BitrixRestClientTest {

    @Test
    void buildsTheOfficialWebhookEndpoints() {
        String base = "https://portal.bitrix24.ru/rest/216/secret/";

        assertThat(BitrixApiTransport.FORM_JSON_SUFFIX.endpoint(base, "disk.file.get"))
            .isEqualTo(base + "disk.file.get.json");
        assertThat(BitrixApiTransport.JSON_JSON_SUFFIX.endpoint(base, "disk.file.get"))
            .isEqualTo(base + "disk.file.get.json");
        assertThat(BitrixApiTransport.JSON_TRAILING_SLASH.endpoint(base, "disk.file.get"))
            .isEqualTo(base + "disk.file.get/");
        assertThat(BitrixApiTransport.JSON_DIRECT.endpoint(base, "disk.file.get"))
            .isEqualTo(base + "disk.file.get");
    }

    @Test
    void flattensNestedObjectsLikePhpHttpBuildQuery() {
        BitrixRestClient client = new BitrixRestClient(null);
        MultiValueMap<String, String> form = client.toFormBody(Map.of(
            "botId", 1084,
            "fields", Map.of(
                "message", "hello",
                "users", List.of(1, 2)
            )
        ));

        assertThat(form.getFirst("botId")).isEqualTo("1084");
        assertThat(form.getFirst("fields[message]")).isEqualTo("hello");
        assertThat(form.getFirst("fields[users][0]")).isEqualTo("1");
        assertThat(form.getFirst("fields[users][1]")).isEqualTo("2");
    }
}
