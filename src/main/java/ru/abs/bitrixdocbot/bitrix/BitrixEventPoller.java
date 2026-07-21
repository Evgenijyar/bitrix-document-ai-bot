package ru.abs.bitrixdocbot.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixEventPoller {

    private static final Logger log = LoggerFactory.getLogger(BitrixEventPoller.class);

    private final ConfigurationService configurationService;
    private final BitrixBotService botService;
    private final BitrixMessageProcessor messageProcessor;
    private volatile boolean previouslyReady;

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
        boolean ready = configuration.getBitrix().isReadyForPolling();
        if (!ready) {
            if (previouslyReady) {
                log.warn("BITRIX POLLER paused because bot configuration is no longer complete");
            }
            previouslyReady = false;
            log.trace("BITRIX POLLER skipped webhookConfigured={} botId={} tokenPresent={}",
                configuration.getBitrix().getWebhookUrl() != null
                    && !configuration.getBitrix().getWebhookUrl().isBlank(),
                configuration.getBitrix().getBotId(),
                configuration.getBitrix().getBotToken() != null
                    && !configuration.getBitrix().getBotToken().isBlank());
            return;
        }
        if (!previouslyReady) {
            log.info("BITRIX POLLER activated botId={} webhook={} offset={}",
                configuration.getBitrix().getBotId(),
                LogSanitizer.maskWebhook(configuration.getBitrix().getWebhookUrl()),
                configuration.getBitrix().getEventOffset());
        }
        previouslyReady = true;

        long started = System.nanoTime();
        try {
            JsonNode response = botService.getEvents(configuration.getBitrix());
            JsonNode result = response.path("result");
            JsonNode events = result.path("events");
            int eventCount = events.isArray() ? events.size() : 0;
            int messageCount = 0;

            if (events.isArray()) {
                for (JsonNode event : events) {
                    String type = event.path("type").asString();
                    log.debug("BITRIX EVENT received type={} event={}", type, LogSanitizer.sanitizeJson(event));
                    if ("ONIMBOTV2MESSAGEADD".equalsIgnoreCase(type)) {
                        messageCount++;
                        messageProcessor.process(event.path("data"));
                    }
                }
            }

            Long previousOffset = configuration.getBitrix().getEventOffset();
            Long nextOffset = result.path("nextOffset").canConvertToLong()
                ? result.path("nextOffset").asLong()
                : null;
            if (nextOffset != null && !nextOffset.equals(previousOffset)) {
                configurationService.updateEventOffset(nextOffset);
            }

            long durationMs = (System.nanoTime() - started) / 1_000_000;
            if (eventCount > 0 || messageCount > 0) {
                log.info("BITRIX POLLER completed botId={} events={} messageEvents={} previousOffset={} nextOffset={} durationMs={}",
                    configuration.getBitrix().getBotId(), eventCount, messageCount, previousOffset, nextOffset, durationMs);
            } else {
                log.debug("BITRIX POLLER completed botId={} events=0 offset={} durationMs={}",
                    configuration.getBitrix().getBotId(), nextOffset, durationMs);
            }
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("BITRIX POLLER failed botId={} offset={} durationMs={} message={}",
                configuration.getBitrix().getBotId(),
                configuration.getBitrix().getEventOffset(),
                durationMs,
                exception.getMessage(),
                exception);
        }
    }
}
