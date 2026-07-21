package ru.abs.bitrixdocbot.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.dto.BitrixSettingsDto;
import ru.abs.bitrixdocbot.admin.dto.BotConfigurationDto;
import ru.abs.bitrixdocbot.admin.dto.ModelSettingsDto;
import ru.abs.bitrixdocbot.domain.BitrixSettings;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import ru.abs.bitrixdocbot.storage.ConfigurationStore;

@Service
public class ConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConfigurationStore store;

    public ConfigurationService(ConfigurationStore store) {
        this.store = store;
    }

    public BotConfiguration getInternalSnapshot() {
        return store.getSnapshot();
    }

    public BotConfigurationDto getForAdmin() {
        BotConfiguration snapshot = store.getSnapshot();
        log.debug("CONFIG snapshot prepared for admin simple={} complex={} bitrixWebhook={} botId={}",
            modelSummary(snapshot.getSimpleModel()),
            modelSummary(snapshot.getComplexModel()),
            LogSanitizer.maskWebhook(snapshot.getBitrix().getWebhookUrl()),
            snapshot.getBitrix().getBotId());
        return toDto(snapshot);
    }

    public BotConfigurationDto saveFromAdmin(BotConfigurationDto dto) {
        BotConfiguration current = store.getSnapshot();
        BotConfiguration updated = new BotConfiguration();

        boolean simpleKeySubmitted = dto.getSimpleModel() != null
            && dto.getSimpleModel().getApiKey() != null
            && !dto.getSimpleModel().getApiKey().isBlank();
        boolean complexKeySubmitted = dto.getComplexModel() != null
            && dto.getComplexModel().getApiKey() != null
            && !dto.getComplexModel().getApiKey().isBlank();

        updated.setSimpleModel(toModel(dto.getSimpleModel(), current.getSimpleModel()));
        updated.setComplexModel(toModel(dto.getComplexModel(), current.getComplexModel()));
        updated.setBitrix(toBitrix(dto.getBitrix(), current.getBitrix()));
        updated.setRelevancePrompt(nullToEmpty(dto.getRelevancePrompt()));
        updated.setAnalysisPrompt(nullToEmpty(dto.getAnalysisPrompt()));
        updated.setIrrelevantReply(nullToEmpty(dto.getIrrelevantReply()));
        updated.setProcessingReply(nullToEmpty(dto.getProcessingReply()));
        updated.setErrorReply(nullToEmpty(dto.getErrorReply()));
        updated.setMaxFileCount(dto.getMaxFileCount());
        updated.setMaxFileSizeBytes(dto.getMaxFileSizeBytes());
        updated.setMaxExtractedCharsPerFile(dto.getMaxExtractedCharsPerFile());
        updated.setMaxTotalExtractedChars(dto.getMaxTotalExtractedChars());
        updated.setOutgoingMessageChunkSize(dto.getOutgoingMessageChunkSize());

        log.info("CONFIG save details simple={} simpleKeySubmitted={} complex={} complexKeySubmitted={} "
                + "bitrixWebhook={} botCode={} botName={} registrationReset={} prompts=[relevance:{},analysis:{}] "
                + "limits=[files:{},fileBytes:{},charsPerFile:{},totalChars:{},chunk:{}]",
            modelSummary(updated.getSimpleModel()),
            simpleKeySubmitted,
            modelSummary(updated.getComplexModel()),
            complexKeySubmitted,
            LogSanitizer.maskWebhook(updated.getBitrix().getWebhookUrl()),
            updated.getBitrix().getBotCode(),
            updated.getBitrix().getBotName(),
            current.getBitrix().getBotId() != null && updated.getBitrix().getBotId() == null,
            updated.getRelevancePrompt().length(),
            updated.getAnalysisPrompt().length(),
            updated.getMaxFileCount(),
            updated.getMaxFileSizeBytes(),
            updated.getMaxExtractedCharsPerFile(),
            updated.getMaxTotalExtractedChars(),
            updated.getOutgoingMessageChunkSize());

        BotConfiguration saved = store.replace(updated);
        log.info("CONFIG save persisted botId={} botTokenConfigured={} eventOffset={}",
            saved.getBitrix().getBotId(),
            saved.getBitrix().getBotToken() != null && !saved.getBitrix().getBotToken().isBlank(),
            saved.getBitrix().getEventOffset());
        return toDto(saved);
    }

    public void updateBotRegistration(long botId, String botToken) {
        log.info("CONFIG bot registration persistence started botId={} tokenPresent={}", botId,
            botToken != null && !botToken.isBlank());
        store.update(config -> {
            config.getBitrix().setBotId(botId);
            config.getBitrix().setBotToken(botToken);
            config.getBitrix().setEventOffset(null);
        });
        log.info("CONFIG bot registration persisted botId={} eventOffsetReset=true", botId);
    }

    public void updateEventOffset(long nextOffset) {
        store.update(config -> config.getBitrix().setEventOffset(nextOffset));
        log.debug("CONFIG Bitrix event offset persisted nextOffset={}", nextOffset);
    }

    private ModelSettings toModel(ModelSettingsDto dto, ModelSettings current) {
        if (dto == null) {
            throw new IllegalArgumentException("Model settings are missing");
        }
        ModelSettings settings = new ModelSettings();
        settings.setProvider(dto.getProvider());
        settings.setEndpoint(nullToEmpty(dto.getEndpoint()).trim());
        settings.setModelId(nullToEmpty(dto.getModelId()).trim());
        String submittedKey = nullToEmpty(dto.getApiKey()).trim();
        settings.setApiKey(submittedKey.isBlank() ? current.getApiKey() : submittedKey);
        return settings;
    }

    private BitrixSettings toBitrix(BitrixSettingsDto dto, BitrixSettings current) {
        if (dto == null) {
            throw new IllegalArgumentException("Bitrix24 settings are missing");
        }
        BitrixSettings settings = new BitrixSettings();
        String newWebhook = normalizeWebhook(dto.getWebhookUrl());
        String newBotCode = nullToEmpty(dto.getBotCode()).trim();
        boolean registrationIdentityChanged = !newWebhook.equals(current.getWebhookUrl())
            || !newBotCode.equals(current.getBotCode());

        if (registrationIdentityChanged) {
            log.info("CONFIG Bitrix registration identity changed oldWebhook={} newWebhook={} oldCode={} newCode={}; "
                    + "stored bot registration will be reset",
                LogSanitizer.maskWebhook(current.getWebhookUrl()),
                LogSanitizer.maskWebhook(newWebhook),
                current.getBotCode(),
                newBotCode);
        }

        settings.setWebhookUrl(newWebhook);
        settings.setBotId(registrationIdentityChanged ? null : current.getBotId());
        settings.setBotToken(registrationIdentityChanged ? "" : current.getBotToken());
        settings.setEventOffset(registrationIdentityChanged ? null : current.getEventOffset());
        settings.setBotCode(newBotCode);
        settings.setBotName(nullToEmpty(dto.getBotName()).trim());
        settings.setWorkPosition(nullToEmpty(dto.getWorkPosition()).trim());
        return settings;
    }

    private BotConfigurationDto toDto(BotConfiguration source) {
        BotConfigurationDto dto = new BotConfigurationDto();
        dto.setSimpleModel(toDto(source.getSimpleModel()));
        dto.setComplexModel(toDto(source.getComplexModel()));
        dto.setBitrix(toDto(source.getBitrix()));
        dto.setRelevancePrompt(source.getRelevancePrompt());
        dto.setAnalysisPrompt(source.getAnalysisPrompt());
        dto.setIrrelevantReply(source.getIrrelevantReply());
        dto.setProcessingReply(source.getProcessingReply());
        dto.setErrorReply(source.getErrorReply());
        dto.setMaxFileCount(source.getMaxFileCount());
        dto.setMaxFileSizeBytes(source.getMaxFileSizeBytes());
        dto.setMaxExtractedCharsPerFile(source.getMaxExtractedCharsPerFile());
        dto.setMaxTotalExtractedChars(source.getMaxTotalExtractedChars());
        dto.setOutgoingMessageChunkSize(source.getOutgoingMessageChunkSize());
        return dto;
    }

    private ModelSettingsDto toDto(ModelSettings source) {
        ModelSettingsDto dto = new ModelSettingsDto();
        dto.setProvider(source.getProvider());
        dto.setEndpoint(source.getEndpoint());
        dto.setModelId(source.getModelId());
        dto.setApiKey("");
        dto.setApiKeyConfigured(source.getApiKey() != null && !source.getApiKey().isBlank());
        return dto;
    }

    private BitrixSettingsDto toDto(BitrixSettings source) {
        BitrixSettingsDto dto = new BitrixSettingsDto();
        dto.setWebhookUrl(source.getWebhookUrl());
        dto.setBotId(source.getBotId());
        dto.setBotCode(source.getBotCode());
        dto.setBotName(source.getBotName());
        dto.setWorkPosition(source.getWorkPosition());
        dto.setBotTokenConfigured(source.getBotToken() != null && !source.getBotToken().isBlank());
        return dto;
    }

    private String modelSummary(ModelSettings settings) {
        if (settings == null) {
            return "<missing>";
        }
        return "provider=" + settings.getProvider()
            + ",endpoint=" + LogSanitizer.safeEndpoint(settings.getEndpoint())
            + ",modelId=" + LogSanitizer.shortValue(settings.getModelId(), 100)
            + ",keyConfigured=" + (settings.getApiKey() != null && !settings.getApiKey().isBlank());
    }

    private String normalizeWebhook(String value) {
        String normalized = nullToEmpty(value).trim();
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
