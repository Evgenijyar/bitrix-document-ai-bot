package ru.abs.bitrixdocbot.bitrix;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
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

    public byte[] downloadFile(BitrixSettings bitrix, long fileId) {
        log.info("BITRIX FILE metadata request started botId={} fileId={}", bitrix.getBotId(), fileId);
        Map<String, Object> request = Map.of(
            "botId", bitrix.getBotId(),
            "botToken", bitrix.getBotToken(),
            "fileId", fileId
        );
        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.File.download", request);
        String downloadUrl = response.path("result").path("downloadUrl").asString();
        if (downloadUrl.isBlank()) {
            log.error("BITRIX FILE metadata response has no downloadUrl fileId={} response={}",
                fileId, LogSanitizer.sanitizeJson(response));
            throw new BitrixApiException("Bitrix24 did not return downloadUrl for file " + fileId);
        }
        log.info("BITRIX FILE download URL received fileId={} url={}", fileId, LogSanitizer.safeEndpoint(downloadUrl));
        byte[] content = bitrixRestClient.download(downloadUrl);
        log.info("BITRIX FILE download completed fileId={} bytes={}", fileId, content.length);
        return content;
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
