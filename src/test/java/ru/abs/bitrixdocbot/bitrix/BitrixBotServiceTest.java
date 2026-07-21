package ru.abs.bitrixdocbot.bitrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.document.BitrixAttachment;
import ru.abs.bitrixdocbot.document.DownloadedBitrixFile;
import ru.abs.bitrixdocbot.domain.BitrixSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BitrixBotServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private BitrixRestClient restClient;
    private BitrixBotService service;
    private BitrixSettings settings;

    @BeforeEach
    void setUp() {
        restClient = mock(BitrixRestClient.class);
        service = new BitrixBotService(restClient, mock(ConfigurationService.class));
        settings = new BitrixSettings();
        settings.setWebhookUrl("https://portal.bitrix24.ru/rest/216/webhook-secret/");
        settings.setBotId(1084L);
        settings.setBotToken("bot-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadsChatFileThroughAuthorizedUserMethod() throws Exception {
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        JsonNode metadata = mapper.readTree("""
            {"result":{"ID":239596,"NAME":"contract.pdf","SIZE":9,"GLOBAL_CONTENT_VERSION":2}}
            """);
        JsonNode downloadResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/download.json?token=im%7Cabc"}}
            """);

        when(restClient.call(settings.getWebhookUrl(), "disk.file.get", Map.of("id", 239596L)))
            .thenReturn(metadata);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), any()))
            .thenReturn(downloadResponse);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?token=im%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings, "216", new BitrixAttachment(239596L, "bitrix-file-239596", null));

        ArgumentCaptor<Object> requestCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restClient).call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), requestCaptor.capture());
        Map<String, Object> request = (Map<String, Object>) requestCaptor.getValue();
        assertThat(request).containsExactlyInAnyOrderEntriesOf(Map.of(
            "dialogId", "1084",
            "fileId", 239596L
        ));
        assertThat(result.source()).isEqualTo("im.v2.File.download");
        assertThat(result.fileName()).isEqualTo("contract.pdf");
        assertThat(result.declaredSize()).isEqualTo(9L);
        assertThat(result.content()).isEqualTo(bytes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToBotMethodWhenImScopeIsUnavailable() throws Exception {
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        JsonNode metadata = mapper.readTree("""
            {"result":{"ID":239596,"NAME":"contract.pdf","SIZE":9}}
            """);
        JsonNode botResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/download.json?token=imbot%7Cabc"}}
            """);

        when(restClient.call(settings.getWebhookUrl(), "disk.file.get", Map.of("id", 239596L)))
            .thenReturn(metadata);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), any()))
            .thenThrow(new BitrixApiException("insufficient_scope"));
        when(restClient.call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), any()))
            .thenReturn(botResponse);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?token=imbot%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings, "216", new BitrixAttachment(239596L, "contract.pdf", 9L));

        ArgumentCaptor<Object> requestCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restClient).call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), requestCaptor.capture());
        Map<String, Object> request = (Map<String, Object>) requestCaptor.getValue();
        assertThat(request).containsEntry("botId", 1084L)
            .containsEntry("botToken", "bot-secret")
            .containsEntry("fileId", 239596L);
        assertThat(result.source()).isEqualTo("imbot.v2.File.download");
    }

    @Test
    void fallsBackToCurrentDiskVersionWhenChatMethodsAreUnavailable() throws Exception {
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        JsonNode metadata = mapper.readTree("""
            {"result":{"ID":239596,"NAME":"contract.pdf","SIZE":9,"GLOBAL_CONTENT_VERSION":2}}
            """);
        JsonNode versions = mapper.readTree("""
            {"result":[{"ID":7001,"OBJECT_ID":239596,"NAME":"contract.pdf","SIZE":9,
              "GLOBAL_CONTENT_VERSION":2,
              "DOWNLOAD_URL":"https://portal.bitrix24.ru/rest/download.json?auth=x&token=disk%7Cabc"}]}
            """);

        when(restClient.call(settings.getWebhookUrl(), "disk.file.get", Map.of("id", 239596L)))
            .thenReturn(metadata);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), any()))
            .thenThrow(new BitrixApiException("temporary error"));
        when(restClient.call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), any()))
            .thenThrow(new BitrixApiException("temporary error"));
        when(restClient.call(settings.getWebhookUrl(), "disk.file.getVersions", Map.of("id", 239596L, "start", 0)))
            .thenReturn(versions);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?auth=x&token=disk%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings, "216", new BitrixAttachment(239596L, "contract.pdf", 9L));

        assertThat(result.source()).isEqualTo("disk.file.getVersions");
        assertThat(result.content()).isEqualTo(bytes);
    }

    @Test
    void mapsPrivateBotEventDialogToBotIdForAuthorizedUserApi() {
        assertThat(service.resolveAuthorizedUserDialogCandidates(settings, "216"))
            .containsExactly("1084", "216");
    }

    @Test
    void keepsGroupDialogIdForAuthorizedUserApi() {
        assertThat(service.resolveAuthorizedUserDialogCandidates(settings, "chat87000"))
            .containsExactly("chat87000");
    }

    @Test
    void acceptsOnlySignedRestDownloadJsonUrls() {
        assertThat(service.isUsableDownloadUrl(
            "https://portal.bitrix24.ru/rest/216/webhook-secret/download/",
            settings.getWebhookUrl()
        )).isFalse();

        assertThat(service.isUsableDownloadUrl(
            "https://portal.bitrix24.ru/rest/download.json?token=im%7Cabc",
            settings.getWebhookUrl()
        )).isTrue();
    }
}
