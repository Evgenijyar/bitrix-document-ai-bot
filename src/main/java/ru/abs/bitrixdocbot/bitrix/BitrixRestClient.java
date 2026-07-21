package ru.abs.bitrixdocbot.bitrix;

import java.lang.reflect.Array;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixRestClient {

    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();
    private static final int MAX_ERROR_BODY_CHARS = 2_000;
    private static final String USER_AGENT = "bitrix-document-ai-bot/0.1.14";

    private final RestClient.Builder restClientBuilder;

    public BitrixRestClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * Keeps the already proven JSON transport for ordinary bot operations.
     * File-link methods deliberately use {@link #call(String, String, Object, BitrixApiTransport)}
     * with a transport matrix because their generated URLs depend on the exact webhook wire format.
     */
    public JsonNode call(String webhookUrl, String method, Object request) {
        return call(webhookUrl, method, request, BitrixApiTransport.JSON_DIRECT);
    }

    public JsonNode call(
        String webhookUrl,
        String method,
        Object request,
        BitrixApiTransport transport
    ) {
        validateWebhook(webhookUrl);
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        String endpoint = transport.endpoint(normalizeWebhookBase(webhookUrl), method);

        log.info("BITRIX API -> callId={} method={} transport={} endpoint={} request={}",
            callId,
            method,
            transport.logName(),
            LogSanitizer.safeEndpoint(endpoint),
            LogSanitizer.sanitize(request));

        try {
            RestClient.RequestBodySpec specification = restClientBuilder.build()
                .post()
                .uri(URI.create(endpoint))
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .accept(MediaType.APPLICATION_JSON);

            if (transport.formEncoded()) {
                specification.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(toFormBody(request));
            } else {
                specification.contentType(MediaType.APPLICATION_JSON)
                    .body(request);
            }

            JsonNode response = specification
                .retrieve()
                .onStatus(status -> status.isError(), (httpRequest, httpResponse) -> {
                    String responseBody = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("BITRIX API HTTP ERROR callId={} method={} transport={} status={} body={}",
                        callId,
                        method,
                        transport.logName(),
                        httpResponse.getStatusCode(),
                        LogSanitizer.shortValue(responseBody, MAX_ERROR_BODY_CHARS));
                    throw new BitrixApiException("Bitrix24 HTTP " + httpResponse.getStatusCode()
                        + " for " + method + " via " + transport.logName() + ": "
                        + LogSanitizer.shortValue(responseBody, 1_000));
                })
                .body(JsonNode.class);

            validateApiResponse(method, transport, response, callId);

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("BITRIX API <- callId={} method={} transport={} durationMs={} response={}",
                callId,
                method,
                transport.logName(),
                durationMs,
                LogSanitizer.sanitizeJson(response));
            return response;
        } catch (BitrixApiException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX API !! callId={} method={} transport={} durationMs={} message={}",
                callId, method, transport.logName(), durationMs, exception.getMessage(), exception);
            throw exception;
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX API !! callId={} method={} transport={} durationMs={} exception={} message={}",
                callId,
                method,
                transport.logName(),
                durationMs,
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                exception);
            throw new BitrixApiException("Bitrix24 call failed for " + method + " via "
                + transport.logName() + ": " + safeMessage(exception), exception);
        }
    }

    /**
     * Downloads the exact URL returned by Bitrix24. URI.create is intentional: it preserves the
     * signed query string byte-for-byte instead of decoding and encoding it a second time.
     */
    public byte[] download(String url) {
        validateDownloadUrl(url);
        long callId = CALL_SEQUENCE.incrementAndGet();
        long started = System.nanoTime();
        log.info("BITRIX FILE -> callId={} url={}", callId, LogSanitizer.safeEndpoint(url));
        try {
            byte[] data = restClientBuilder.build()
                .get()
                .uri(URI.create(url))
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
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
        validateDownloadUrl(url);
        return executeDownload(url, "POST_JSON", specification -> specification
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload));
    }

    public byte[] downloadPostForm(String url, Map<String, Object> payload) {
        validateDownloadUrl(url);
        return executeDownload(url, "POST_FORM", specification -> specification
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(toFormBody(payload)));
    }

    MultiValueMap<String, String> toFormBody(Object request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (request instanceof Map<?, ?> map) {
            map.forEach((key, value) -> appendFormValue(form, String.valueOf(key), value));
        } else if (request != null) {
            throw new IllegalArgumentException("Bitrix24 form request must be represented as a map");
        }
        return form;
    }

    private void appendFormValue(MultiValueMap<String, String> form, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((nestedKey, nestedValue) ->
                appendFormValue(form, key + "[" + nestedKey + "]", nestedValue));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                appendFormValue(form, key + "[" + index + "]", item);
                index++;
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                appendFormValue(form, key + "[" + index + "]", Array.get(value, index));
            }
            return;
        }
        form.add(key, String.valueOf(value));
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
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
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

    private void validateApiResponse(
        String method,
        BitrixApiTransport transport,
        JsonNode response,
        long callId
    ) {
        if (response == null) {
            throw new BitrixApiException("Bitrix24 returned an empty response for " + method);
        }
        if (response.path("error").isString()) {
            String error = response.path("error").asString();
            String description = response.path("error_description").asString();
            log.error("BITRIX API BUSINESS ERROR callId={} method={} transport={} error={} description={} response={}",
                callId,
                method,
                transport.logName(),
                error,
                LogSanitizer.shortValue(description, 1_000),
                LogSanitizer.sanitizeJson(response));
            throw new BitrixApiException(error + ": " + description);
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

    private String normalizeWebhookBase(String webhookUrl) {
        return webhookUrl.endsWith("/") ? webhookUrl : webhookUrl + "/";
    }

    private void validateWebhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("https://")) {
            log.error("BITRIX webhook validation failed webhook={}", LogSanitizer.maskWebhook(webhookUrl));
            throw new IllegalArgumentException("Bitrix24 incoming webhook URL must start with https://");
        }
    }

    private void validateDownloadUrl(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new BitrixApiException("Bitrix24 returned an invalid HTTPS download URL");
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : LogSanitizer.shortValue(message, 1_000);
    }
}
