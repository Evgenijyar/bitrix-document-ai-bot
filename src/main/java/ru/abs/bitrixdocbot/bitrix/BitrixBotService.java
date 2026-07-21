package ru.abs.bitrixdocbot.bitrix;

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.document.BitrixAttachment;
import ru.abs.bitrixdocbot.document.DownloadedBitrixFile;
import ru.abs.bitrixdocbot.domain.BitrixSettings;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixBotService {

    private static final Logger log = LoggerFactory.getLogger(BitrixBotService.class);

    private final BitrixRestClient bitrixRestClient;
    private final ConfigurationService configurationService;
    private final SecureRandom secureRandom = new SecureRandom();

    public BitrixBotService(BitrixRestClient bitrixRestClient, ConfigurationService configurationService) {
        this.bitrixRestClient = bitrixRestClient;
        this.configurationService = configurationService;
    }

    public JsonNode registerBot() {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        BitrixSettings bitrix = configuration.getBitrix();

        log.info("BITRIX BOT registration requested webhook={} code={} name={} workPosition={} existingBotId={} "
                + "storedTokenPresent={}",
            LogSanitizer.maskWebhook(bitrix.getWebhookUrl()),
            bitrix.getBotCode(),
            bitrix.getBotName(),
            bitrix.getWorkPosition(),
            bitrix.getBotId(),
            bitrix.getBotToken() != null && !bitrix.getBotToken().isBlank());

        validateRegistration(bitrix);
        log.info("BITRIX BOT registration settings validated code={}", bitrix.getBotCode());

        String token = bitrix.getBotToken();
        boolean generatedNewToken = token == null || token.isBlank();
        if (generatedNewToken) {
            token = generateBotToken();
        }
        log.info("BITRIX BOT token prepared generatedNewToken={} tokenLength={}", generatedNewToken, token.length());

        Map<String, Object> properties = Map.of(
            "name", bitrix.getBotName(),
            "workPosition", bitrix.getWorkPosition()
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", bitrix.getBotCode());
        fields.put("botToken", token);
        fields.put("properties", properties);
        fields.put("type", "bot");
        fields.put("eventMode", "fetch");

        Map<String, Object> request = Map.of("fields", fields);
        log.info("BITRIX BOT calling imbot.v2.Bot.register code={} eventMode=fetch type=bot", bitrix.getBotCode());

        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Bot.register", request);
        log.info("BITRIX BOT register response received response={}", LogSanitizer.sanitizeJson(response));

        long botId = extractBotId(response.path("result"));
        log.info("BITRIX BOT ID extracted botId={}", botId);
        configurationService.updateBotRegistration(botId, token);
        log.info("BITRIX BOT registration completed botId={} code={}", botId, bitrix.getBotCode());
        return response;
    }

    public JsonNode checkBot() {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        BitrixSettings bitrix = configuration.getBitrix();
        log.info("BITRIX BOT check requested webhook={} botId={} code={} tokenPresent={}",
            LogSanitizer.maskWebhook(bitrix.getWebhookUrl()),
            bitrix.getBotId(),
            bitrix.getBotCode(),
            bitrix.getBotToken() != null && !bitrix.getBotToken().isBlank());

        if (bitrix.getWebhookUrl() == null || bitrix.getWebhookUrl().isBlank()) {
            throw new IllegalStateException("Bitrix24 webhook is not configured. Save settings first.");
        }
        if (bitrix.getBotToken() == null || bitrix.getBotToken().isBlank()) {
            throw new IllegalStateException("Bot token is not configured. Register the bot first.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("botToken", bitrix.getBotToken());
        if (bitrix.getBotId() != null) {
            request.put("botId", bitrix.getBotId());
            log.info("BITRIX BOT check will search by botId={}", bitrix.getBotId());
        } else {
            request.put("code", bitrix.getBotCode());
            log.info("BITRIX BOT check will search by code={}", bitrix.getBotCode());
        }
        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Bot.get", request);
        log.info("BITRIX BOT check completed response={}", LogSanitizer.sanitizeJson(response));
        return response;
    }

    public JsonNode getEvents(BitrixSettings bitrix) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("botId", bitrix.getBotId());
        request.put("botToken", bitrix.getBotToken());
        request.put("limit", 50);
        if (bitrix.getEventOffset() != null) {
            request.put("offset", bitrix.getEventOffset());
        }
        log.debug("BITRIX EVENTS request botId={} offset={} limit=50", bitrix.getBotId(), bitrix.getEventOffset());
        return bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Event.get", request);
    }

    public void sendMessage(BitrixSettings bitrix, String dialogId, String message) {
        log.info("BITRIX MESSAGE send started botId={} dialogId={} chars={}",
            bitrix.getBotId(), dialogId, message == null ? 0 : message.length());
        Map<String, Object> request = Map.of(
            "botId", bitrix.getBotId(),
            "botToken", bitrix.getBotToken(),
            "dialogId", dialogId,
            "fields", Map.of("message", message)
        );
        bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Chat.Message.send", request);
        log.info("BITRIX MESSAGE send completed botId={} dialogId={} chars={}",
            bitrix.getBotId(), dialogId, message == null ? 0 : message.length());
    }

    /**
     * Downloads a file attached to a message.
     *
     * Primary route: imbot.v2.File.download with the dialogId from the actual event.
     * Fallback route: disk.file.get, which supplies both real metadata and a machine download URL.
     * The fallback requires the existing incoming webhook to have the additional {@code disk} scope.
     */
    public DownloadedBitrixFile downloadFile(
        BitrixSettings bitrix,
        String dialogId,
        BitrixAttachment attachment
    ) {
        long fileId = attachment.fileId();
        log.info("BITRIX FILE acquisition started botId={} dialogId={} fileId={} eventName={} eventBytes={}",
            bitrix.getBotId(), dialogId, fileId, attachment.fileName(), attachment.declaredSize());

        List<String> failures = new ArrayList<>();

        try {
            DownloadedBitrixFile downloaded = downloadViaBotApi(bitrix, dialogId, attachment);
            log.info("BITRIX FILE acquisition completed fileId={} source={} name={} bytes={}",
                fileId, downloaded.source(), downloaded.fileName(), downloaded.content().length);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("imbot.v2.File.download: " + concise(exception));
            log.warn("BITRIX FILE primary route failed fileId={} dialogId={} reason={} fallback=disk.file.get",
                fileId, dialogId, concise(exception));
        }

        try {
            DownloadedBitrixFile downloaded = downloadViaDiskApi(bitrix, attachment);
            log.info("BITRIX FILE acquisition completed fileId={} source={} name={} bytes={}",
                fileId, downloaded.source(), downloaded.fileName(), downloaded.content().length);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("disk.file.get: " + concise(exception));
            log.error("BITRIX FILE fallback route failed fileId={} reason={}", fileId, concise(exception), exception);
        }

        throw new BitrixApiException(
            "Не удалось получить файл из Bitrix24. "
                + "Добавьте право disk к существующему входящему вебхуку Bitrix24 и повторите отправку файла. "
                + String.join("; ", failures)
        );
    }

    private DownloadedBitrixFile downloadViaBotApi(
        BitrixSettings bitrix,
        String dialogId,
        BitrixAttachment attachment
    ) {
        long fileId = attachment.fileId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("botId", bitrix.getBotId());
        request.put("botToken", bitrix.getBotToken());
        // The official quick-start example includes dialogId. Some cloud revisions generate a bad
        // webhook-like URL when it is omitted, even though the dedicated method page marks only fileId as required.
        if (dialogId != null && !dialogId.isBlank()) {
            request.put("dialogId", dialogId);
        }
        request.put("fileId", fileId);

        log.info("BITRIX FILE primary metadata request method=imbot.v2.File.download fileId={} dialogId={}",
            fileId, dialogId);
        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.File.download", request);
        String downloadUrl = firstText(response.path("result"), "downloadUrl", "DOWNLOAD_URL");
        if (downloadUrl.isBlank()) {
            throw new BitrixApiException("Bitrix24 did not return downloadUrl for file " + fileId);
        }
        if (!isUsableDownloadUrl(downloadUrl, bitrix.getWebhookUrl())) {
            throw new BitrixApiException("Bitrix24 returned a non-machine download URL: "
                + LogSanitizer.safeEndpoint(downloadUrl));
        }

        log.info("BITRIX FILE primary URL accepted fileId={} url={}",
            fileId, LogSanitizer.safeEndpoint(downloadUrl));
        byte[] content = bitrixRestClient.download(downloadUrl);
        return new DownloadedBitrixFile(
            fileId,
            attachment.fileName(),
            attachment.declaredSize(),
            content,
            "imbot.v2.File.download"
        );
    }

    private DownloadedBitrixFile downloadViaDiskApi(
        BitrixSettings bitrix,
        BitrixAttachment attachment
    ) {
        long fileId = attachment.fileId();
        log.info("BITRIX FILE fallback metadata request method=disk.file.get fileId={}", fileId);

        JsonNode response;
        try {
            response = bitrixRestClient.call(bitrix.getWebhookUrl(), "disk.file.get", Map.of("id", fileId));
        } catch (BitrixApiException exception) {
            if (isScopeError(exception)) {
                throw new BitrixApiException(
                    "Webhook does not have disk scope. Add the disk permission to the existing incoming webhook.",
                    exception
                );
            }
            throw exception;
        }

        JsonNode result = response.path("result");
        String downloadUrl = firstText(result, "DOWNLOAD_URL", "downloadUrl");
        if (downloadUrl.isBlank()) {
            throw new BitrixApiException("disk.file.get did not return DOWNLOAD_URL for file " + fileId);
        }
        if (!isUsableDownloadUrl(downloadUrl, bitrix.getWebhookUrl())) {
            throw new BitrixApiException("disk.file.get returned an invalid download URL: "
                + LogSanitizer.safeEndpoint(downloadUrl));
        }

        String realName = firstText(result, "NAME", "name");
        Long realSize = firstLongOrNull(result, "SIZE", "size");
        String resolvedName = realName.isBlank() ? attachment.fileName() : realName;
        Long resolvedSize = realSize == null ? attachment.declaredSize() : realSize;

        log.info("BITRIX FILE fallback metadata received fileId={} name={} declaredBytes={} url={}",
            fileId, resolvedName, resolvedSize, LogSanitizer.safeEndpoint(downloadUrl));
        byte[] content = bitrixRestClient.download(downloadUrl);
        return new DownloadedBitrixFile(
            fileId,
            resolvedName,
            resolvedSize,
            content,
            "disk.file.get"
        );
    }

    boolean isUsableDownloadUrl(String value, String webhookUrl) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            URI webhook = URI.create(webhookUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            if (webhook.getHost() != null && !webhook.getHost().equalsIgnoreCase(uri.getHost())) {
                return false;
            }

            String path = normalizePath(uri.getPath());
            String webhookPath = normalizePath(webhook.getPath());
            boolean looksLikeWebhookMethod = path.equals(webhookPath + "/download")
                || path.matches("/rest/[^/]+/[^/]+/download");
            boolean hasMachineToken = uri.getRawQuery() != null
                && (uri.getRawQuery().contains("token=")
                    || uri.getRawQuery().contains("auth=")
                    || uri.getRawQuery().contains("sessid="));

            // Official machine URLs normally look like /rest/download.json?...token=...
            // The bad URL observed on this portal looks like /rest/{user}/{webhook}/download/.
            return !looksLikeWebhookMethod || hasMachineToken;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isScopeError(BitrixApiException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("insufficient_scope")
            || normalized.contains("higher privileges")
            || normalized.contains("invalid_credentials")
            || normalized.contains("scope")
            || normalized.contains("error_method_not_found")
            || normalized.contains("method not found");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replaceAll("/+$", "");
        return normalized.isBlank() ? "/" : normalized;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Long firstLongOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber() || value.isString()) {
                long parsed = value.asLong(-1);
                if (parsed >= 0) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private String concise(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : LogSanitizer.shortValue(message, 500);
    }

    private long extractBotId(JsonNode result) {
        log.debug("BITRIX BOT extracting ID from result={}", LogSanitizer.sanitizeJson(result));
        if (result.isIntegralNumber()) {
            return result.asLong();
        }
        for (String field : new String[]{"id", "botId", "ID", "BOT_ID"}) {
            if (result.path(field).canConvertToLong()) {
                return result.path(field).asLong();
            }
        }
        if (result.path("bot").path("id").canConvertToLong()) {
            return result.path("bot").path("id").asLong();
        }
        throw new BitrixApiException("Cannot determine bot ID from Bitrix24 response: "
            + LogSanitizer.sanitizeJson(result));
    }

    private String generateBotToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validateRegistration(BitrixSettings settings) {
        if (settings.getWebhookUrl() == null || settings.getWebhookUrl().isBlank()) {
            log.error("BITRIX BOT registration validation failed: webhook URL is empty");
            throw new IllegalArgumentException("Bitrix24 webhook URL is empty");
        }
        if (settings.getBotCode() == null || settings.getBotCode().isBlank()) {
            log.error("BITRIX BOT registration validation failed: bot code is empty");
            throw new IllegalArgumentException("Bot code is empty");
        }
        if (settings.getBotName() == null || settings.getBotName().isBlank()) {
            log.error("BITRIX BOT registration validation failed: bot name is empty");
            throw new IllegalArgumentException("Bot name is empty");
        }
    }
}
