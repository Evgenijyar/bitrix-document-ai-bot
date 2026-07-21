package ru.abs.bitrixdocbot.bitrix;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
     * Downloads the real binary file attached to a Bitrix24 chat message.
     *
     * The reliable route for incoming-webhook integrations is:
     * disk.file.get -> disk.file.getVersions -> disk.version.get (when needed) -> exact signed DOWNLOAD_URL.
     * Bitrix24 documents these version URLs as /rest/download.json?...token=disk..., and they contain
     * the authorization token required to fetch the binary payload.
     */
    public DownloadedBitrixFile downloadFile(
        BitrixSettings bitrix,
        String dialogId,
        BitrixAttachment attachment
    ) {
        long fileId = attachment.fileId();
        log.info("BITRIX FILE acquisition started botId={} dialogId={} diskObjectId={} eventName={} eventBytes={}",
            bitrix.getBotId(), dialogId, fileId, attachment.fileName(), attachment.declaredSize());

        List<String> failures = new ArrayList<>();

        try {
            DownloadedBitrixFile downloaded = downloadViaDiskVersionApi(bitrix, attachment);
            log.info("BITRIX FILE acquisition completed diskObjectId={} source={} name={} bytes={}",
                fileId, downloaded.source(), downloaded.fileName(), downloaded.content().length);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("disk.file.getVersions: " + concise(exception));
            log.warn("BITRIX FILE disk-version route failed diskObjectId={} reason={} fallback=imbot.v2.File.download",
                fileId, concise(exception));
        }

        try {
            DownloadedBitrixFile downloaded = downloadViaBotApi(bitrix, dialogId, attachment);
            log.info("BITRIX FILE acquisition completed diskObjectId={} source={} name={} bytes={}",
                fileId, downloaded.source(), downloaded.fileName(), downloaded.content().length);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("imbot.v2.File.download: " + concise(exception));
            log.error("BITRIX FILE bot route failed diskObjectId={} reason={}",
                fileId, concise(exception), exception);
        }

        throw new BitrixApiException(
            "Не удалось скачать бинарный файл из Bitrix24. " + String.join("; ", failures)
        );
    }

    private DownloadedBitrixFile downloadViaDiskVersionApi(
        BitrixSettings bitrix,
        BitrixAttachment attachment
    ) {
        long diskObjectId = attachment.fileId();
        log.info("BITRIX FILE metadata request method=disk.file.get diskObjectId={}", diskObjectId);

        JsonNode metadataResponse;
        try {
            metadataResponse = bitrixRestClient.call(
                bitrix.getWebhookUrl(), "disk.file.get", Map.of("id", diskObjectId));
        } catch (BitrixApiException exception) {
            if (isScopeError(exception)) {
                throw new BitrixApiException(
                    "Вебхуку Bitrix24 требуется право disk и право чтения файла.", exception);
            }
            throw exception;
        }

        JsonNode metadata = metadataResponse.path("result");
        String realName = firstText(metadata, "NAME", "name");
        Long realSize = firstLongOrNull(metadata, "SIZE", "size");
        Long globalContentVersion = firstLongOrNull(
            metadata, "GLOBAL_CONTENT_VERSION", "globalContentVersion");
        String resolvedName = realName.isBlank() ? attachment.fileName() : realName;
        Long resolvedSize = realSize == null ? attachment.declaredSize() : realSize;

        log.info("BITRIX FILE metadata received diskObjectId={} internalFileId={} name={} bytes={} globalVersion={}",
            diskObjectId,
            firstLongOrNull(metadata, "FILE_ID", "fileId"),
            resolvedName,
            resolvedSize,
            globalContentVersion);

        JsonNode versionsResponse = bitrixRestClient.call(
            bitrix.getWebhookUrl(),
            "disk.file.getVersions",
            Map.of("id", diskObjectId, "start", 0)
        );
        JsonNode versions = versionsResponse.path("result");
        if (!versions.isArray() || versions.isEmpty()) {
            throw new BitrixApiException("disk.file.getVersions returned no versions for file " + diskObjectId);
        }

        JsonNode selectedVersion = selectCurrentVersion(versions, globalContentVersion);
        Long versionId = firstLongOrNull(selectedVersion, "ID", "id");
        String versionName = firstText(selectedVersion, "NAME", "name");
        Long versionSize = firstLongOrNull(selectedVersion, "SIZE", "size");
        String downloadUrl = firstText(selectedVersion, "DOWNLOAD_URL", "downloadUrl");

        if (!versionName.isBlank()) {
            resolvedName = versionName;
        }
        if (versionSize != null) {
            resolvedSize = versionSize;
        }

        // Request the selected version once more when the list response does not contain a canonical
        // signed URL. This produces a fresh one-time DOWNLOAD_URL according to the Disk API contract.
        if (!isUsableDownloadUrl(downloadUrl, bitrix.getWebhookUrl()) && versionId != null) {
            log.info("BITRIX FILE refreshing signed URL method=disk.version.get versionId={}", versionId);
            JsonNode versionResponse = bitrixRestClient.call(
                bitrix.getWebhookUrl(), "disk.version.get", Map.of("id", versionId));
            JsonNode version = versionResponse.path("result");
            String refreshedName = firstText(version, "NAME", "name");
            Long refreshedSize = firstLongOrNull(version, "SIZE", "size");
            downloadUrl = firstText(version, "DOWNLOAD_URL", "downloadUrl");
            if (!refreshedName.isBlank()) {
                resolvedName = refreshedName;
            }
            if (refreshedSize != null) {
                resolvedSize = refreshedSize;
            }
        }

        if (!isUsableDownloadUrl(downloadUrl, bitrix.getWebhookUrl())) {
            throw new BitrixApiException(
                "Bitrix24 did not return a signed /rest/download.json URL for file " + diskObjectId);
        }

        log.info("BITRIX FILE signed URL ready diskObjectId={} versionId={} name={} expectedBytes={} url={}",
            diskObjectId, versionId, resolvedName, resolvedSize, LogSanitizer.safeEndpoint(downloadUrl));

        byte[] content = bitrixRestClient.download(downloadUrl);
        validateDownloadedDocumentBytes(resolvedName, resolvedSize, content);
        return new DownloadedBitrixFile(
            diskObjectId,
            resolvedName,
            resolvedSize,
            content,
            "disk.file.getVersions"
        );
    }

    private JsonNode selectCurrentVersion(JsonNode versions, Long globalContentVersion) {
        JsonNode first = null;
        for (JsonNode version : versions.values()) {
            if (first == null) {
                first = version;
            }
            Long candidate = firstLongOrNull(
                version, "GLOBAL_CONTENT_VERSION", "globalContentVersion");
            if (globalContentVersion != null && globalContentVersion.equals(candidate)) {
                return version;
            }
        }
        if (first == null) {
            throw new BitrixApiException("Bitrix24 returned an empty file version list");
        }
        return first;
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
        if (dialogId != null && !dialogId.isBlank()) {
            request.put("dialogId", dialogId);
        }
        request.put("fileId", fileId);

        JsonNode response = bitrixRestClient.call(
            bitrix.getWebhookUrl(), "imbot.v2.File.download", request);
        String downloadUrl = firstText(response.path("result"), "downloadUrl", "DOWNLOAD_URL");
        if (!isUsableDownloadUrl(downloadUrl, bitrix.getWebhookUrl())) {
            throw new BitrixApiException(
                "imbot.v2.File.download did not return a canonical signed download URL");
        }

        byte[] content = bitrixRestClient.download(downloadUrl);
        validateDownloadedDocumentBytes(attachment.fileName(), attachment.declaredSize(), content);
        return new DownloadedBitrixFile(
            fileId,
            attachment.fileName(),
            attachment.declaredSize(),
            content,
            "imbot.v2.File.download"
        );
    }

    private void validateDownloadedDocumentBytes(String fileName, Long expectedSize, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BitrixApiException("Downloaded Bitrix24 file is empty");
        }
        if (expectedSize != null && expectedSize > 0 && expectedSize.longValue() != content.length) {
            throw new BitrixApiException(
                "Downloaded file size mismatch: expected " + expectedSize + ", received " + content.length);
        }

        String prefix = new String(content, 0, Math.min(content.length, 512), StandardCharsets.UTF_8)
            .stripLeading().toLowerCase(Locale.ROOT);
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.contains("<title>")) {
            throw new BitrixApiException("Bitrix24 returned HTML instead of the document binary");
        }

        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            requireMagic(content, "%PDF-".getBytes(StandardCharsets.US_ASCII), "PDF");
        } else if (lowerName.endsWith(".doc")) {
            requireMagic(content,
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                "DOC");
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".odt")
            || lowerName.endsWith(".xlsx") || lowerName.endsWith(".pptx")) {
            requireMagic(content, new byte[]{0x50, 0x4B}, "ZIP-based Office document");
        }
    }

    private void requireMagic(byte[] content, byte[] expected, String type) {
        if (content.length < expected.length) {
            throw new BitrixApiException("Downloaded content is not a valid " + type + " file");
        }
        for (int index = 0; index < expected.length; index++) {
            if (content[index] != expected[index]) {
                throw new BitrixApiException("Downloaded content is not a valid " + type + " file");
            }
        }
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
            String query = uri.getRawQuery();
            return "/rest/download.json".equalsIgnoreCase(path)
                && query != null
                && (query.contains("token=") || query.contains("auth="));
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
