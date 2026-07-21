package ru.abs.bitrixdocbot.bitrix;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixRestClient {

    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();

    private final RestClient.Builder restClientBuilder;

    public BitrixRestClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public JsonNode call(String webhookUrl, String method, Object request) {
        validateWebhook(webhookUrl);
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        String endpoint = (webhookUrl.endsWith("/") ? webhookUrl : webhookUrl + "/") + method;

        log.info("BITRIX API -> callId={} method={} webhook={} request={}",
            callId,
            method,
            LogSanitizer.maskWebhook(webhookUrl),
            LogSanitizer.sanitize(request));

        try {
            JsonNode response = restClientBuilder.build()
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("BITRIX API HTTP ERROR callId={} method={} status={} body={}",
                        callId,
                        method,
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, 2_000));
                    throw new BitrixApiException("Bitrix24 HTTP " + httpResponse.getStatusCode()
                        + " for " + method + ": " + LogSanitizer.shortValue(responseBody, 1_000));
                })
                .body(JsonNode.class);

            if (response == null) {
                throw new BitrixApiException("Bitrix24 returned an empty response for " + method);
            }
            if (response.path("error").isString()) {
                String error = response.path("error").asString();
                String description = response.path("error_description").asString();
                log.error("BITRIX API BUSINESS ERROR callId={} method={} error={} description={} response={}",
                    callId,
                    method,
                    error,
                    LogSanitizer.shortValue(description, 1_000),
                    LogSanitizer.sanitizeJson(response));
                throw new BitrixApiException(error + ": " + description);
            }

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("BITRIX API <- callId={} method={} durationMs={} response={}",
                callId,
                method,
                durationMs,
                LogSanitizer.sanitizeJson(response));
            return response;
        } catch (BitrixApiException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX API !! callId={} method={} durationMs={} message={}",
                callId, method, durationMs, exception.getMessage(), exception);
            throw exception;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX API !! callId={} method={} durationMs={} exception={} message={}",
                callId,
                method,
                durationMs,
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                exception);
            throw new BitrixApiException("Bitrix24 call failed for " + method + ": " + safeMessage(exception), exception);
        }
    }

    public byte[] download(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid download URL");
        }
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        log.info("BITRIX FILE -> callId={} url={}", callId, LogSanitizer.safeEndpoint(url));
        try {
            byte[] data = restClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("BITRIX FILE HTTP ERROR callId={} status={} body={}",
                        callId,
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, 1_000));
                    throw new BitrixApiException("Bitrix24 file download HTTP " + httpResponse.getStatusCode());
                })
                .body(byte[].class);
            if (data == null) {
                throw new BitrixApiException("Downloaded file is empty");
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("BITRIX FILE <- callId={} bytes={} durationMs={}", callId, data.length, durationMs);
            return data;
        } catch (BitrixApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("BITRIX FILE !! callId={} message={}", callId, exception.getMessage(), exception);
            throw new BitrixApiException("Bitrix24 file download failed: " + safeMessage(exception), exception);
        }
    }

    private void validateWebhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("https://")) {
            log.error("BITRIX webhook validation failed webhook={}", LogSanitizer.maskWebhook(webhookUrl));
            throw new IllegalArgumentException("Bitrix24 incoming webhook URL must start with https://");
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : LogSanitizer.shortValue(message, 1_000);
    }
}
