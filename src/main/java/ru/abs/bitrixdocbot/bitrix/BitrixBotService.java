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
    private static final List<BitrixApiTransport> FILE_API_TRANSPORTS = List.of(
        BitrixApiTransport.FORM_JSON_SUFFIX,
        BitrixApiTransport.JSON_JSON_SUFFIX,
        BitrixApiTransport.JSON_TRAILING_SLASH,
        BitrixApiTransport.JSON_DIRECT
    );

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
     * File methods are intentionally executed through every officially used incoming-webhook
     * transport. The Bitrix24 request builder normally emits *.json URLs, CRest posts URL-encoded
     * forms to those URLs, while the newer SDK uses JSON and a trailing slash. A portal can accept
     * ordinary REST calls through a legacy URL and still generate a broken file link. We therefore
     * do not infer the link from the response: each returned URL is requested exactly as supplied
     * and accepted only after its bytes pass the size and file-signature checks.
     */
    public DownloadedBitrixFile downloadFile(
        BitrixSettings bitrix,
        String dialogId,
        BitrixAttachment attachment
    ) {
        return downloadFile(bitrix, dialogId, null, attachment);
    }

    public DownloadedBitrixFile downloadFile(
        BitrixSettings bitrix,
        String dialogId,
        String chatDialogId,
        BitrixAttachment attachment
    ) {
        long diskObjectId = attachment.fileId();
        ResolvedFileMetadata metadata = resolveFileMetadata(bitrix, attachment);

        log.info("BITRIX FILE acquisition started botId={} dialogId={} chatDialogId={} diskObjectId={} "
                + "internalFileId={} name={} expectedBytes={} metadataTransport={}",
            bitrix.getBotId(), dialogId, chatDialogId, diskObjectId, metadata.internalFileId(),
            metadata.fileName(), metadata.size(), metadata.transport());

        List<String> failures = new ArrayList<>();

        if (metadata.downloadUrl() != null && !metadata.downloadUrl().isBlank()) {
            try {
                DownloadedBitrixFile downloaded = downloadReturnedUrl(
                    bitrix,
                    diskObjectId,
                    metadata.fileName(),
                    metadata.size(),
                    metadata.downloadUrl(),
                    "disk.file.get/" + metadata.transport());
                logAcquisitionCompleted(downloaded);
                return downloaded;
            } catch (BitrixApiException exception) {
                failures.add("disk.file.get URL: " + concise(exception));
                log.warn("BITRIX FILE metadata URL failed diskObjectId={} reason={} fallback=im.v2.File.download",
                    diskObjectId, concise(exception));
            }
        }

        List<Long> fileIdCandidates = resolveFileIdCandidates(diskObjectId, metadata.internalFileId());

        try {
            DownloadedBitrixFile downloaded = downloadViaAuthorizedUserApi(
                bitrix, dialogId, chatDialogId, fileIdCandidates, metadata.fileName(), metadata.size());
            logAcquisitionCompleted(downloaded);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("im.v2.File.download: " + concise(exception));
            log.warn("BITRIX FILE user-chat route failed diskObjectId={} reason={} fallback=imbot.v2.File.download",
                diskObjectId, concise(exception));
        }

        try {
            DownloadedBitrixFile downloaded = downloadViaBotApi(
                bitrix, fileIdCandidates, metadata.fileName(), metadata.size());
            logAcquisitionCompleted(downloaded);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("imbot.v2.File.download: " + concise(exception));
            log.warn("BITRIX FILE bot route failed diskObjectId={} reason={} fallback=disk.file.getVersions",
                diskObjectId, concise(exception));
        }

        try {
            DownloadedBitrixFile downloaded = downloadViaDiskVersionApi(
                bitrix, diskObjectId, metadata.fileName(), metadata.size(), metadata.globalContentVersion());
            logAcquisitionCompleted(downloaded);
            return downloaded;
        } catch (BitrixApiException exception) {
            failures.add("disk.file.getVersions: " + concise(exception));
            log.error("BITRIX FILE disk-version route failed diskObjectId={} reason={}",
                diskObjectId, concise(exception), exception);
        }

        throw new BitrixApiException(
            "Не удалось скачать бинарный файл из Bitrix24 после проверки всех официальных "
                + "транспортов webhook и обоих идентификаторов файла. " + String.join("; ", failures)
        );
    }

    private ResolvedFileMetadata resolveFileMetadata(
        BitrixSettings bitrix,
        BitrixAttachment attachment
    ) {
        long diskObjectId = attachment.fileId();
        ResolvedFileMetadata fallback = null;
        List<String> failures = new ArrayList<>();

        for (BitrixApiTransport transport : FILE_API_TRANSPORTS) {
            try {
                log.info("BITRIX FILE metadata request method=disk.file.get diskObjectId={} transport={}",
                    diskObjectId, transport.logName());
                JsonNode response = bitrixRestClient.call(
                    bitrix.getWebhookUrl(), "disk.file.get", Map.of("id", diskObjectId), transport);
                JsonNode metadata = response.path("result");

                String name = firstText(metadata, "NAME", "name");
                Long size = firstLongOrNull(metadata, "SIZE", "size");
                Long globalContentVersion = firstLongOrNull(
                    metadata, "GLOBAL_CONTENT_VERSION", "globalContentVersion");
                Long internalFileId = firstLongOrNull(metadata, "FILE_ID", "fileId");
                String downloadUrl = firstText(metadata, "DOWNLOAD_URL", "downloadUrl");
                String resolvedName = name.isBlank() ? attachment.fileName() : name;
                Long resolvedSize = size == null ? attachment.declaredSize() : size;

                ResolvedFileMetadata candidate = new ResolvedFileMetadata(
                    resolvedName,
                    resolvedSize,
                    globalContentVersion,
                    internalFileId,
                    downloadUrl,
                    transport.logName()
                );
                if (fallback == null) {
                    fallback = candidate;
                }

                log.info("BITRIX FILE metadata received diskObjectId={} internalFileId={} name={} bytes={} "
                        + "globalVersion={} transport={} url={}",
                    diskObjectId, internalFileId, resolvedName, resolvedSize, globalContentVersion,
                    transport.logName(), LogSanitizer.safeEndpoint(downloadUrl));

                if (looksLikeSignedMachineUrl(downloadUrl, bitrix.getWebhookUrl())) {
                    return candidate;
                }
            } catch (BitrixApiException exception) {
                failures.add(transport.logName() + ": " + concise(exception));
                log.warn("BITRIX FILE metadata transport failed diskObjectId={} transport={} reason={}",
                    diskObjectId, transport.logName(), concise(exception));
            }
        }

        if (fallback != null) {
            return fallback;
        }

        log.warn("BITRIX FILE metadata unavailable diskObjectId={} failures={} usingEventMetadata=true",
            diskObjectId, String.join("; ", failures));
        return new ResolvedFileMetadata(
            attachment.fileName(), attachment.declaredSize(), null, null, "", "event");
    }

    private DownloadedBitrixFile downloadViaAuthorizedUserApi(
        BitrixSettings bitrix,
        String eventDialogId,
        String chatDialogId,
        List<Long> fileIds,
        String fileName,
        Long expectedSize
    ) {
        List<String> dialogCandidates = resolveAuthorizedUserDialogCandidates(bitrix, eventDialogId, chatDialogId);
        List<String> failures = new ArrayList<>();

        for (String dialogCandidate : dialogCandidates) {
            for (Long fileId : fileIds) {
                try {
                    log.info("BITRIX FILE authorized-user candidate eventDialogId={} chatDialogId={} "
                            + "apiDialogId={} botId={} fileId={}",
                        eventDialogId, chatDialogId, dialogCandidate, bitrix.getBotId(), fileId);
                    return downloadViaFileLinkMethod(
                        bitrix,
                        "im.v2.File.download",
                        Map.of("dialogId", dialogCandidate, "fileId", fileId),
                        fileId,
                        fileName,
                        expectedSize
                    );
                } catch (BitrixApiException exception) {
                    failures.add("dialog=" + dialogCandidate + ",file=" + fileId + ": " + concise(exception));
                    log.warn("BITRIX FILE authorized-user candidate failed apiDialogId={} fileId={} reason={}",
                        dialogCandidate, fileId, concise(exception));
                }
            }
        }

        throw new BitrixApiException(
            "im.v2.File.download failed for all dialog/file candidates: " + String.join("; ", failures));
    }

    List<String> resolveAuthorizedUserDialogCandidates(
        BitrixSettings bitrix,
        String eventDialogId,
        String chatDialogId
    ) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, chatDialogId);

        if (eventDialogId != null && eventDialogId.regionMatches(true, 0, "chat", 0, 4)) {
            addCandidate(candidates, eventDialogId);
            return List.copyOf(candidates);
        }

        if (bitrix.getBotId() != null && bitrix.getBotId() > 0) {
            addCandidate(candidates, String.valueOf(bitrix.getBotId()));
        }
        addCandidate(candidates, eventDialogId);
        return List.copyOf(candidates);
    }

    List<String> resolveAuthorizedUserDialogCandidates(BitrixSettings bitrix, String eventDialogId) {
        return resolveAuthorizedUserDialogCandidates(bitrix, eventDialogId, null);
    }

    private DownloadedBitrixFile downloadViaBotApi(
        BitrixSettings bitrix,
        List<Long> fileIds,
        String fileName,
        Long expectedSize
    ) {
        List<String> failures = new ArrayList<>();
        for (Long fileId : fileIds) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("botId", bitrix.getBotId());
            request.put("botToken", bitrix.getBotToken());
            request.put("fileId", fileId);
            try {
                return downloadViaFileLinkMethod(
                    bitrix,
                    "imbot.v2.File.download",
                    request,
                    fileId,
                    fileName,
                    expectedSize
                );
            } catch (BitrixApiException exception) {
                failures.add("file=" + fileId + ": " + concise(exception));
            }
        }
        throw new BitrixApiException(
            "imbot.v2.File.download failed for all file IDs: " + String.join("; ", failures));
    }

    private DownloadedBitrixFile downloadViaFileLinkMethod(
        BitrixSettings bitrix,
        String method,
        Map<String, Object> request,
        long returnedFileId,
        String fileName,
        Long expectedSize
    ) {
        List<String> failures = new ArrayList<>();

        for (BitrixApiTransport transport : FILE_API_TRANSPORTS) {
            try {
                JsonNode response = bitrixRestClient.call(
                    bitrix.getWebhookUrl(), method, request, transport);
                String downloadUrl = firstText(response.path("result"), "downloadUrl", "DOWNLOAD_URL");
                if (downloadUrl.isBlank()) {
                    throw new BitrixApiException("Bitrix24 returned an empty downloadUrl");
                }
                return downloadReturnedUrl(
                    bitrix,
                    returnedFileId,
                    fileName,
                    expectedSize,
                    downloadUrl,
                    method + "/" + transport.logName()
                );
            } catch (BitrixApiException exception) {
                failures.add(transport.logName() + ": " + concise(exception));
                log.warn("BITRIX FILE link transport failed method={} transport={} fileId={} reason={}",
                    method, transport.logName(), returnedFileId, concise(exception));
            }
        }

        throw new BitrixApiException(method + " failed for all webhook transports: "
            + String.join("; ", failures));
    }

    private DownloadedBitrixFile downloadViaDiskVersionApi(
        BitrixSettings bitrix,
        long diskObjectId,
        String initialName,
        Long initialSize,
        Long globalContentVersion
    ) {
        List<String> failures = new ArrayList<>();

        for (BitrixApiTransport transport : FILE_API_TRANSPORTS) {
            try {
                JsonNode versionsResponse = bitrixRestClient.call(
                    bitrix.getWebhookUrl(),
                    "disk.file.getVersions",
                    Map.of("id", diskObjectId, "start", 0),
                    transport
                );
                JsonNode versions = versionsResponse.path("result");
                if (!versions.isArray() || versions.isEmpty()) {
                    throw new BitrixApiException("disk.file.getVersions returned no versions");
                }

                JsonNode selectedVersion = selectCurrentVersion(versions, globalContentVersion);
                Long versionId = firstLongOrNull(selectedVersion, "ID", "id");
                String versionName = firstText(selectedVersion, "NAME", "name");
                Long versionSize = firstLongOrNull(selectedVersion, "SIZE", "size");
                String downloadUrl = firstText(selectedVersion, "DOWNLOAD_URL", "downloadUrl");
                String resolvedName = versionName.isBlank() ? initialName : versionName;
                Long resolvedSize = versionSize == null ? initialSize : versionSize;

                if (!downloadUrl.isBlank()) {
                    try {
                        return downloadReturnedUrl(
                            bitrix,
                            diskObjectId,
                            resolvedName,
                            resolvedSize,
                            downloadUrl,
                            "disk.file.getVersions/" + transport.logName()
                        );
                    } catch (BitrixApiException exception) {
                        failures.add(transport.logName() + "/versions-url: " + concise(exception));
                    }
                }

                if (versionId != null) {
                    JsonNode versionResponse = bitrixRestClient.call(
                        bitrix.getWebhookUrl(),
                        "disk.version.get",
                        Map.of("id", versionId),
                        transport
                    );
                    JsonNode version = versionResponse.path("result");
                    String refreshedName = firstText(version, "NAME", "name");
                    Long refreshedSize = firstLongOrNull(version, "SIZE", "size");
                    String refreshedUrl = firstText(version, "DOWNLOAD_URL", "downloadUrl");
                    if (!refreshedName.isBlank()) {
                        resolvedName = refreshedName;
                    }
                    if (refreshedSize != null) {
                        resolvedSize = refreshedSize;
                    }
                    if (!refreshedUrl.isBlank()) {
                        return downloadReturnedUrl(
                            bitrix,
                            diskObjectId,
                            resolvedName,
                            resolvedSize,
                            refreshedUrl,
                            "disk.version.get/" + transport.logName()
                        );
                    }
                }

                throw new BitrixApiException("Bitrix24 returned no downloadable version URL");
            } catch (BitrixApiException exception) {
                failures.add(transport.logName() + ": " + concise(exception));
                log.warn("BITRIX FILE disk-version transport failed diskObjectId={} transport={} reason={}",
                    diskObjectId, transport.logName(), concise(exception));
            }
        }

        throw new BitrixApiException("disk version methods failed for all webhook transports: "
            + String.join("; ", failures));
    }

    private DownloadedBitrixFile downloadReturnedUrl(
        BitrixSettings bitrix,
        long fileId,
        String fileName,
        Long expectedSize,
        String returnedUrl,
        String source
    ) {
        String downloadUrl = normalizeReturnedDownloadUrl(returnedUrl, bitrix.getWebhookUrl());
        log.info("BITRIX FILE returned URL attempt source={} fileId={} url={}",
            source, fileId, LogSanitizer.safeEndpoint(downloadUrl));
        byte[] content = bitrixRestClient.download(downloadUrl);
        validateDownloadedDocumentBytes(fileName, expectedSize, content);
        return new DownloadedBitrixFile(fileId, fileName, expectedSize, content, source);
    }

    String normalizeReturnedDownloadUrl(String value, String webhookUrl) {
        if (value == null || value.isBlank()) {
            throw new BitrixApiException("Bitrix24 returned an empty download URL");
        }
        try {
            URI webhook = URI.create(webhookUrl.trim());
            URI candidate = URI.create(value.trim());
            URI resolved = candidate.isAbsolute()
                ? candidate
                : new URI(webhook.getScheme(), webhook.getAuthority(), "/", null, null).resolve(candidate);
            if (!"https".equalsIgnoreCase(resolved.getScheme()) || resolved.getHost() == null) {
                throw new BitrixApiException("Bitrix24 returned a non-HTTPS download URL");
            }
            if (webhook.getHost() != null && !webhook.getHost().equalsIgnoreCase(resolved.getHost())) {
                throw new BitrixApiException("Bitrix24 returned a download URL for another host");
            }
            return resolved.toASCIIString();
        } catch (BitrixApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BitrixApiException("Bitrix24 returned a malformed download URL", exception);
        }
    }

    boolean looksLikeSignedMachineUrl(String value, String webhookUrl) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(normalizeReturnedDownloadUrl(value, webhookUrl));
            String query = uri.getRawQuery();
            String path = normalizePath(uri.getPath());
            return ("/rest/download.json".equalsIgnoreCase(path)
                    || path.toLowerCase(Locale.ROOT).endsWith("/download"))
                && query != null
                && (query.contains("token=") || query.contains("auth=") || query.contains("id="));
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean isUsableDownloadUrl(String value, String webhookUrl) {
        try {
            normalizeReturnedDownloadUrl(value, webhookUrl);
            return true;
        } catch (BitrixApiException exception) {
            return false;
        }
    }

    private List<Long> resolveFileIdCandidates(long diskObjectId, Long internalFileId) {
        List<Long> candidates = new ArrayList<>();
        candidates.add(diskObjectId);
        if (internalFileId != null && internalFileId > 0 && internalFileId != diskObjectId) {
            candidates.add(internalFileId);
        }
        return List.copyOf(candidates);
    }

    private void addCandidate(List<String> candidates, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    private void logAcquisitionCompleted(DownloadedBitrixFile downloaded) {
        log.info("BITRIX FILE acquisition completed fileId={} source={} name={} bytes={}",
            downloaded.fileId(), downloaded.source(), downloaded.fileName(), downloaded.content().length);
    }

    private record ResolvedFileMetadata(
        String fileName,
        Long size,
        Long globalContentVersion,
        Long internalFileId,
        String downloadUrl,
        String transport
    ) {
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
