package ru.abs.bitrixdocbot.domain;

public class BitrixSettings {

    private String webhookUrl = "";
    private Long botId;
    private String botToken = "";
    private String botCode = "document_ai_bot";
    private String botName = "Анализ документов";
    private String workPosition = "AI-анализ договоров";
    private Long eventOffset;

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

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
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

    public Long getEventOffset() {
        return eventOffset;
    }

    public void setEventOffset(Long eventOffset) {
        this.eventOffset = eventOffset;
    }

    public boolean isReadyForPolling() {
        return webhookUrl != null && !webhookUrl.isBlank()
            && botId != null
            && botToken != null && !botToken.isBlank();
    }
}
