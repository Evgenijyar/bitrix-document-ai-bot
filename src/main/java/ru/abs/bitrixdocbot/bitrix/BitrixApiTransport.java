package ru.abs.bitrixdocbot.bitrix;

/**
 * Wire formats accepted by Bitrix24 incoming webhooks.
 *
 * The first two variants mirror the official webhook generator / CRest clients.
 * The remaining variants are compatibility fallbacks for portals that accept JSON-only calls.
 */
public enum BitrixApiTransport {
    FORM_JSON_SUFFIX("form-json-suffix", true, EndpointStyle.JSON_SUFFIX),
    JSON_JSON_SUFFIX("json-json-suffix", false, EndpointStyle.JSON_SUFFIX),
    JSON_TRAILING_SLASH("json-trailing-slash", false, EndpointStyle.TRAILING_SLASH),
    JSON_DIRECT("json-direct", false, EndpointStyle.DIRECT);

    private final String logName;
    private final boolean formEncoded;
    private final EndpointStyle endpointStyle;

    BitrixApiTransport(String logName, boolean formEncoded, EndpointStyle endpointStyle) {
        this.logName = logName;
        this.formEncoded = formEncoded;
        this.endpointStyle = endpointStyle;
    }

    public String logName() {
        return logName;
    }

    public boolean formEncoded() {
        return formEncoded;
    }

    String endpoint(String webhookBase, String method) {
        return switch (endpointStyle) {
            case JSON_SUFFIX -> webhookBase + method + ".json";
            case TRAILING_SLASH -> webhookBase + method + "/";
            case DIRECT -> webhookBase + method;
        };
    }

    private enum EndpointStyle {
        JSON_SUFFIX,
        TRAILING_SLASH,
        DIRECT
    }
}
