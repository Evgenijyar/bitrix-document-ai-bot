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
    void downloadsChatFileThroughOfficialFormJsonTransport() throws Exception {
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        JsonNode metadata = mapper.readTree("""
            {"result":{"ID":239596,"FILE_ID":275538,"NAME":"contract.pdf","SIZE":9}}
            """);
        JsonNode downloadResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/download.json?token=im%7Cabc"}}
            """);

        when(restClient.call(eq(settings.getWebhookUrl()), eq("disk.file.get"), any(), any()))
            .thenReturn(metadata);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), any(),
            eq(BitrixApiTransport.FORM_JSON_SUFFIX)))
            .thenReturn(downloadResponse);
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?token=im%7Cabc"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings,
            "216",
            "chat87000",
            new BitrixAttachment(239596L, "bitrix-file-239596", null));

        ArgumentCaptor<Object> requestCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restClient).call(
            eq(settings.getWebhookUrl()),
            eq("im.v2.File.download"),
            requestCaptor.capture(),
            eq(BitrixApiTransport.FORM_JSON_SUFFIX));
        Map<String, Object> request = (Map<String, Object>) requestCaptor.getValue();
        assertThat(request).containsExactlyInAnyOrderEntriesOf(Map.of(
            "dialogId", "chat87000",
            "fileId", 239596L
        ));
        assertThat(result.source()).isEqualTo("im.v2.File.download/form-json-suffix");
        assertThat(result.fileName()).isEqualTo("contract.pdf");
        assertThat(result.declaredSize()).isEqualTo(9L);
        assertThat(result.content()).isEqualTo(bytes);
    }

    @Test
    void triesInternalFileIdWhenDiskObjectIdDoesNotProduceBinary() throws Exception {
        byte[] bytes = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        JsonNode metadata = mapper.readTree("""
            {"result":{"ID":239596,"FILE_ID":275538,"NAME":"contract.pdf","SIZE":9}}
            """);
        JsonNode badResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/216/webhook-secret/download/"}}
            """);
        JsonNode goodResponse = mapper.readTree("""
            {"result":{"downloadUrl":"https://portal.bitrix24.ru/rest/download.json?token=im%7Cgood"}}
            """);

        when(restClient.call(eq(settings.getWebhookUrl()), eq("disk.file.get"), any(), any()))
            .thenReturn(metadata);
        when(restClient.call(eq(settings.getWebhookUrl()), eq("im.v2.File.download"), any(), any()))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = invocation.getArgument(2);
                return Long.valueOf(275538L).equals(request.get("fileId")) ? goodResponse : badResponse;
            });
        when(restClient.download("https://portal.bitrix24.ru/rest/216/webhook-secret/download/"))
            .thenThrow(new BitrixApiException("404"));
        when(restClient.download("https://portal.bitrix24.ru/rest/download.json?token=im%7Cgood"))
            .thenReturn(bytes);

        DownloadedBitrixFile result = service.downloadFile(
            settings, "216", null, new BitrixAttachment(239596L, "contract.pdf", 9L));

        assertThat(result.content()).isEqualTo(bytes);
        assertThat(result.source()).contains("im.v2.File.download");
    }

    @Test
    void keepsAllDialogPerspectivesInDeterministicOrder() {
        assertThat(service.resolveAuthorizedUserDialogCandidates(settings, "216", "chat87000"))
            .containsExactly("chat87000", "1084", "216");
    }

    @Test
    void acceptsAnyHttpsUrlOnTheSamePortalAndRejectsForeignHost() {
        assertThat(service.isUsableDownloadUrl(
            "https://portal.bitrix24.ru/rest/216/webhook-secret/download/?id=1",
            settings.getWebhookUrl()
        )).isTrue();

        assertThat(service.isUsableDownloadUrl(
            "https://attacker.example/download.pdf",
            settings.getWebhookUrl()
        )).isFalse();
    }
}
