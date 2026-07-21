package ru.abs.bitrixdocbot.bitrix;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.domain.BotConfiguration;

@Service
public class BitrixEventPoller {

    private static final Logger log = LoggerFactory.getLogger(BitrixEventPoller.class);

    private final ConfigurationService configurationService;
    private final BitrixBotService botService;
    private final BitrixMessageProcessor messageProcessor;

    public BitrixEventPoller(
        ConfigurationService configurationService,
        BitrixBotService botService,
        BitrixMessageProcessor messageProcessor
    ) {
        this.configurationService = configurationService;
        this.botService = botService;
        this.messageProcessor = messageProcessor;
    }

    @Scheduled(fixedDelayString = "${app.bitrix.poll-delay-ms:5000}")
    public void poll() {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        if (!configuration.getBitrix().isReadyForPolling()) {
            return;
        }
        try {
            JsonNode response = botService.getEvents(configuration.getBitrix());
            JsonNode result = response.path("result");
            JsonNode events = result.path("events");
            if (events.isArray()) {
                for (JsonNode event : events) {
                    if ("ONIMBOTV2MESSAGEADD".equalsIgnoreCase(event.path("type").asString())) {
                        messageProcessor.process(event.path("data"));
                    }
                }
            }
            if (result.path("nextOffset").canConvertToLong()) {
                configurationService.updateEventOffset(result.path("nextOffset").asLong());
            }
        } catch (Exception exception) {
            log.warn("Bitrix24 polling failed: {}", exception.getMessage());
        }
    }
}
