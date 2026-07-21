package ru.abs.bitrixdocbot.admin.dto;

public class BitrixSettingsDto {

    private String webhookUrl = "";
    private Long botId;
    private String botCode = "document_ai_bot";
    private String botName = "Анализ документов";
    private String workPosition = "AI-анализ договоров";
    private boolean botTokenConfigured;

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public Long getBotId() {
        return botId;
    }

    public void setBotId(Long botId) {
        this.botId = botId;
    }

    public String getBotCode() {
        return botCode;
    }

    public void setBotCode(String botCode) {
        this.botCode = botCode;
    }

    public String getBotName() {
        return botName;
    }

    public void setBotName(String botName) {
        this.botName = botName;
    }

    public String getWorkPosition() {
        return workPosition;
    }

    public void setWorkPosition(String workPosition) {
        this.workPosition = workPosition;
    }

    public boolean isBotTokenConfigured() {
        return botTokenConfigured;
    }

    public void setBotTokenConfigured(boolean botTokenConfigured) {
        this.botTokenConfigured = botTokenConfigured;
    }
}
