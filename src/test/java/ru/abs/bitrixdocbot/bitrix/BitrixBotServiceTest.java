package ru.abs.bitrixdocbot.bitrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void primaryRequestIncludesDialogIdAndDownloadsCanonicalMachineUrl() throws Exception {
        JsonNode response = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/download.json?token=imbot%7Cabc"}}
            """);
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), any()))
            .thenReturn(response);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?token=imbot%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings,
            "216",
            new BitrixAttachment(239596L, "contract.pdf", 100L)
        );

        ArgumentCaptor<Object> requestCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restClient).call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), requestCaptor.capture());
        Map<String, Object> request = (Map<String, Object>) requestCaptor.getValue();
        assertThat(request).containsEntry("dialogId", "216").containsEntry("fileId", 239596L);
        assertThat(result.source()).isEqualTo("imbot.v2.File.download");
        assertThat(result.fileName()).isEqualTo("contract.pdf");
        assertThat(result.content()).isEqualTo(bytes);
    }

    @Test
    void malformedWebhookStyleUrlFallsBackToDiskFileGetAndUsesRealMetadata() throws Exception {
        JsonNode badBotResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/216/webhook-secret/download/"}}
            """);
        JsonNode diskResponse = mapper.readTree("""
            {
              "result": {
                "NAME": "Входящий договор.pdf",
                "SIZE": "2097152",
                "DOWNLOAD_URL": "https://portal.bitrix24.ru/rest/download.json?auth=x&token=disk%7Cabc"
              }
            }
            """);
        byte[] bytes = "%PDF-real".getBytes(StandardCharsets.UTF_8);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), any()))
            .thenReturn(badBotResponse);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("disk.file.get"), any()))
            .thenReturn(diskResponse);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?auth=x&token=disk%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings,
            "216",
            new BitrixAttachment(239596L, "bitrix-file-239596", null)
        );

        assertThat(result.source()).isEqualTo("disk.file.get");
        assertThat(result.fileName()).isEqualTo("Входящий договор.pdf");
        assertThat(result.declaredSize()).isEqualTo(2_097_152L);
        assertThat(result.content()).isEqualTo(bytes);
        verify(restClient).call(eq(settings.getWebhookUrl()), eq("disk.file.get"), eq(Map.of("id", 239596L)));
    }

    @Test
    void missingDiskScopeProducesActionableMessage() throws Exception {
        JsonNode badBotResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/216/webhook-secret/download/"}}
            """);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("imbot.v2.File.download"), any()))
            .thenReturn(badBotResponse);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("disk.file.get"), any()))
            .thenThrow(new BitrixApiException("insufficient_scope: higher privileges required"));

        assertThatThrownBy(() -> service.downloadFile(
            settings,
            "216",
            new BitrixAttachment(239596L, "contract.pdf", null)
        ))
            .isInstanceOf(BitrixApiException.class)
            .hasMessageContaining("Добавьте право disk")
            .hasMessageContaining("Webhook does not have disk scope");
    }

    @Test
    void rejectsObservedBrokenDownloadUrlButAcceptsOfficialMachineUrl() {
        assertThat(service.isUsableDownloadUrl(
            "https://portal.bitrix24.ru/rest/216/webhook-secret/download/",
            settings.getWebhookUrl()
        )).isFalse();

        assertThat(service.isUsableDownloadUrl(
            "https://portal.bitrix24.ru/rest/download.json?token=imbot%7Cabc",
            settings.getWebhookUrl()
        )).isTrue();
    }
}
