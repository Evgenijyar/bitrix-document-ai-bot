package ru.abs.bitrixdocbot.bitrix;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixRestClient {

    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();
    private static final int MAX_ERROR_BODY_CHARS = 2_000;

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
                        LogSanitizer.shortValue(responseBody, MAX_ERROR_BODY_CHARS));
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

    /**
     * Downloads a machine URL returned by Bitrix24. The configured JDK client follows normal redirects.
     * A small JSON REST error body is rejected even when a portal incorrectly returns HTTP 200.
     */
    public byte[] download(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid HTTPS download URL");
        }
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        log.info("BITRIX FILE -> callId={} url={}", callId, LogSanitizer.safeEndpoint(url));
        try {
            byte[] data = restClientBuilder.build()
                .get()
                .uri(URI.create(url))
                .header(HttpHeaders.USER_AGENT, "bitrix-document-ai-bot/0.1.11")
                .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    byte[] responseBytes = httpResponse.getBody().readAllBytes();
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
                    log.error("BITRIX FILE HTTP ERROR callId={} status={} body={}",
                        callId,
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, 1_000));
                    throw new BitrixApiException("Bitrix24 file download HTTP " + httpResponse.getStatusCode()
                        + ": " + LogSanitizer.shortValue(responseBody, 500));
                })
                .body(byte[].class);

            validateDownloadedBytes(data);

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("BITRIX FILE <- callId={} bytes={} durationMs={}", callId, data.length, durationMs);
            return data;
        } catch (BitrixApiException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX FILE !! callId={} durationMs={} message={}",
                callId, durationMs, exception.getMessage(), exception);
            throw exception;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX FILE !! callId={} durationMs={} message={}",
                callId, durationMs, exception.getMessage(), exception);
            throw new BitrixApiException("Bitrix24 file download failed: " + safeMessage(exception), exception);
        }
    }


    public byte[] downloadPostJson(String url, Map<String, Object> payload) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid HTTPS download URL");
        }
        return executeDownload(url, "POST_JSON", spec -> spec
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload));
    }

    public byte[] downloadPostForm(String url, Map<String, Object> payload) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid HTTPS download URL");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                form.add(key, String.valueOf(value));
            }
        });
        return executeDownload(url, "POST_FORM", spec -> spec
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form));
    }

    private byte[] executeDownload(
        String url,
        String mode,
        java.util.function.UnaryOperator<RestClient.RequestBodySpec> requestCustomizer
    ) {
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        log.info("BITRIX FILE -> callId={} mode={} url={}", callId, mode, LogSanitizer.safeEndpoint(url));
        try {
            RestClient.RequestBodySpec request = restClientBuilder.build()
                .post()
                .uri(URI.create(url))
                .header(HttpHeaders.USER_AGENT, "bitrix-document-ai-bot/0.1.11")
                .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);

            byte[] data = requestCustomizer.apply(request)
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    byte[] responseBytes = httpResponse.getBody().readAllBytes();
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
                    log.error("BITRIX FILE HTTP ERROR callId={} mode={} status={} body={}",
                        callId, mode, httpResponse.getStatusCode(), LogSanitizer.shortValue(responseBody, 1_000));
                    throw new BitrixApiException("Bitrix24 file download HTTP " + httpResponse.getStatusCode()
                        + ": " + LogSanitizer.shortValue(responseBody, 500));
                })
                .body(byte[].class);

            validateDownloadedBytes(data);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("BITRIX FILE <- callId={} mode={} bytes={} durationMs={}",
                callId, mode, data.length, durationMs);
            return data;
        } catch (BitrixApiException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX FILE !! callId={} mode={} durationMs={} message={}",
                callId, mode, durationMs, exception.getMessage(), exception);
            throw exception;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX FILE !! callId={} mode={} durationMs={} message={}",
                callId, mode, durationMs, exception.getMessage(), exception);
            throw new BitrixApiException("Bitrix24 file download failed: " + safeMessage(exception), exception);
        }
    }

    private void validateDownloadedBytes(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BitrixApiException("Downloaded Bitrix24 file is empty");
        }
        if (looksLikeRestError(data)) {
            String body = new String(data, StandardCharsets.UTF_8);
            throw new BitrixApiException("Bitrix24 download URL returned a REST error: "
                + LogSanitizer.shortValue(body, 500));
        }
    }

    private boolean looksLikeRestError(byte[] data) {
        if (data.length > 32_768) {
            return false;
        }
        String body = new String(data, StandardCharsets.UTF_8).trim();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            return false;
        }
        return body.contains("\"error\"")
            && (body.contains("\"error_description\"")
                || body.contains("ERROR_METHOD_NOT_FOUND")
                || body.contains("insufficient_scope"));
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
