package ru.abs.bitrixdocbot.logging;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Produces log-safe summaries without API keys, webhook secrets, bot tokens,
 * document text, chat messages or model prompts.
 */
public final class LogSanitizer {

    private static final int DEFAULT_TEXT_LIMIT = 240;

    private LogSanitizer() {
    }

    public static String maskWebhook(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        try {
            URI uri = URI.create(value.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] parts = path.split("/");
            StringBuilder safePath = new StringBuilder();
            int nonEmptyIndex = 0;
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                safePath.append('/');
                if (nonEmptyIndex >= 2) {
                    safePath.append("***");
                    break;
                }
                safePath.append(part);
                nonEmptyIndex++;
            }
            return buildOrigin(uri) + safePath;
        } catch (Exception ignored) {
            return "<invalid-webhook>";
        }
    }

    public static String safeEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        try {
            URI uri = URI.create(value.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            return buildOrigin(uri) + maskWebhookTokenInsidePath(path);
        } catch (Exception ignored) {
            return shortValue(value, DEFAULT_TEXT_LIMIT);
        }
    }

    private static String maskWebhookTokenInsidePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] raw = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String part : raw) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        if (parts.size() >= 3
            && "rest".equalsIgnoreCase(parts.get(0))
            && parts.get(1).matches("\\d+")
            && !"download.json".equalsIgnoreCase(parts.get(2))) {
            parts.set(2, "***");
            return "/" + String.join("/", parts) + (path.endsWith("/") ? "/" : "");
        }
        return path;
    }

    public static String shortValue(String value, int maxLength) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…[" + normalized.length() + " chars]";
    }

    public static Object sanitize(Object value) {
        return sanitizeValue(null, value, 0);
    }

    public static Object sanitizeJson(JsonNode node) {
        return sanitizeJsonNode(null, node, 0);
    }

    private static Object sanitizeValue(String key, Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > 8) {
            return "<max-depth>";
        }
        if (isSecretKey(key)) {
            return "<redacted>";
        }
        if (value instanceof JsonNode jsonNode) {
            return sanitizeJsonNode(key, jsonNode, depth);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((mapKey, mapValue) -> {
                String field = String.valueOf(mapKey);
                result.put(field, sanitizeValue(field, mapValue, depth + 1));
            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                result.add(sanitizeValue(key, item, depth + 1));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                result.add(sanitizeValue(key, Array.get(value, index), depth + 1));
            }
            return result;
        }
        if (value instanceof CharSequence text) {
            return sanitizeTextValue(key, text.toString());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        return shortValue(String.valueOf(value), DEFAULT_TEXT_LIMIT);
    }

    private static Object sanitizeJsonNode(String key, JsonNode node, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (depth > 8) {
            return "<max-depth>";
        }
        if (isSecretKey(key)) {
            return "<redacted>";
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.properties().forEach(entry ->
                result.put(entry.getKey(), sanitizeJsonNode(entry.getKey(), entry.getValue(), depth + 1)));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(child -> result.add(sanitizeJsonNode(key, child, depth + 1)));
            return result;
        }
        if (node.isString()) {
            return sanitizeTextValue(key, node.asString());
        }
        return shortValue(node.toString(), DEFAULT_TEXT_LIMIT);
    }

    private static Object sanitizeTextValue(String key, String value) {
        if (isSecretKey(key)) {
            return "<redacted>";
        }
        if (isWebhookKey(key)) {
            return maskWebhook(value);
        }
        if (isUrlKey(key)) {
            return safeEndpoint(value);
        }
        if (isLargeTextKey(key)) {
            return "<text length=" + value.length() + ">";
        }
        return shortValue(value, DEFAULT_TEXT_LIMIT);
    }

    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("authorization")
            || normalized.equals("auth")
            || normalized.contains("access_token")
            || normalized.contains("bottoken")
            || normalized.contains("bot_token")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("secret");
    }

    private static boolean isWebhookKey(String key) {
        return key != null && key.toLowerCase(Locale.ROOT).contains("webhook");
    }

    private static boolean isUrlKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.endsWith("url") || normalized.endsWith("uri");
    }

    private static boolean isLargeTextKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("message")
            || normalized.equals("text")
            || normalized.equals("content")
            || normalized.equals("input")
            || normalized.equals("instructions")
            || normalized.contains("prompt")
            || normalized.endsWith("reply");
    }

    private static String buildOrigin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme() + "://";
        String host = uri.getHost() == null ? "<unknown-host>" : uri.getHost();
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return scheme + host + port;
    }
}
