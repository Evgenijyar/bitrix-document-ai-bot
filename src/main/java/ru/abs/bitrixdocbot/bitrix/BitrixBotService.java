package ru.abs.bitrixdocbot.bitrix;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.domain.BitrixSettings;
import ru.abs.bitrixdocbot.domain.BotConfiguration;

@Service
public class BitrixBotService {

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
        validateRegistration(bitrix);

        String token = bitrix.getBotToken();
        if (token == null || token.isBlank()) {
            token = generateBotToken();
        }

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

        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Bot.register", request);
        long botId = extractBotId(response.path("result"));
        configurationService.updateBotRegistration(botId, token);
        return response;
    }

    public JsonNode checkBot() {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        BitrixSettings bitrix = configuration.getBitrix();
        if (bitrix.getBotToken() == null || bitrix.getBotToken().isBlank()) {
            throw new IllegalStateException("Bot token is not configured. Register the bot first.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("botToken", bitrix.getBotToken());
        if (bitrix.getBotId() != null) {
            request.put("botId", bitrix.getBotId());
        } else {
            request.put("code", bitrix.getBotCode());
        }
        return bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Bot.get", request);
    }

    public JsonNode getEvents(BitrixSettings bitrix) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("botId", bitrix.getBotId());
        request.put("botToken", bitrix.getBotToken());
        request.put("limit", 50);
        if (bitrix.getEventOffset() != null) {
            request.put("offset", bitrix.getEventOffset());
        }
        return bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Event.get", request);
    }

    public void sendMessage(BitrixSettings bitrix, String dialogId, String message) {
        Map<String, Object> request = Map.of(
            "botId", bitrix.getBotId(),
            "botToken", bitrix.getBotToken(),
            "dialogId", dialogId,
            "fields", Map.of("message", message)
        );
        bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.Chat.Message.send", request);
    }

    public byte[] downloadFile(BitrixSettings bitrix, long fileId) {
        Map<String, Object> request = Map.of(
            "botId", bitrix.getBotId(),
            "botToken", bitrix.getBotToken(),
            "fileId", fileId
        );
        JsonNode response = bitrixRestClient.call(bitrix.getWebhookUrl(), "imbot.v2.File.download", request);
        String downloadUrl = response.path("result").path("downloadUrl").asString();
        return bitrixRestClient.download(downloadUrl);
    }

    private long extractBotId(JsonNode result) {
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
        throw new BitrixApiException("Cannot determine bot ID from Bitrix24 response: " + result);
    }

    private String generateBotToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validateRegistration(BitrixSettings settings) {
        if (settings.getWebhookUrl() == null || settings.getWebhookUrl().isBlank()) {
            throw new IllegalArgumentException("Bitrix24 webhook URL is empty");
        }
        if (settings.getBotCode() == null || settings.getBotCode().isBlank()) {
            throw new IllegalArgumentException("Bot code is empty");
        }
        if (settings.getBotName() == null || settings.getBotName().isBlank()) {
            throw new IllegalArgumentException("Bot name is empty");
        }
    }
}
