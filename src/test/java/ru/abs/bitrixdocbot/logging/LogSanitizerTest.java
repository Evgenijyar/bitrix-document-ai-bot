package ru.abs.bitrixdocbot.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void masksWebhookSecret() {
        String masked = LogSanitizer.maskWebhook("https://portal.bitrix24.ru/rest/1/very-secret-token/");

        assertEquals("https://portal.bitrix24.ru/rest/1/***", masked);
        assertFalse(masked.contains("very-secret-token"));
    }

    @Test
    void removesDownloadQueryToken() {
        Object sanitized = LogSanitizer.sanitize(Map.of(
            "downloadUrl", "https://portal.bitrix24.ru/rest/download.json?token=secret"
        ));

        String text = sanitized.toString();
        assertTrue(text.contains("https://portal.bitrix24.ru/rest/download.json"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("token="));
    }

    @Test
    void redactsSecretsAndLargeText() {
        Object sanitized = LogSanitizer.sanitize(Map.of(
            "botToken", "secret-token",
            "message", "confidential document text",
            "botId", 42
        ));

        String text = sanitized.toString();
        assertTrue(text.contains("<redacted>"));
        assertTrue(text.contains("<text length=26>"));
        assertTrue(text.contains("42"));
        assertFalse(text.contains("secret-token"));
        assertFalse(text.contains("confidential document text"));
    }
}
