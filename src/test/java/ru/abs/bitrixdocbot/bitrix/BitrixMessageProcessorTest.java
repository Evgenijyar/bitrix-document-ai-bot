package ru.abs.bitrixdocbot.bitrix;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.analysis.AnalysisService;
import ru.abs.bitrixdocbot.document.BitrixAttachment;
import ru.abs.bitrixdocbot.document.DocumentBundleBuilder;
import ru.abs.bitrixdocbot.document.DownloadedBitrixFile;
import ru.abs.bitrixdocbot.document.DocumentTextExtractor;
import ru.abs.bitrixdocbot.document.ExtractedDocument;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.message.MessageChunker;
import tools.jackson.databind.JsonNode;

class BitrixMessageProcessorTest {

    private ConfigurationService configurationService;
    private BitrixBotService botService;
    private BitrixEventParser eventParser;
    private DocumentTextExtractor textExtractor;
    private DocumentBundleBuilder bundleBuilder;
    private AnalysisService analysisService;
    private MessageChunker messageChunker;
    private BitrixMessageProcessor processor;
    private BotConfiguration configuration;
    private JsonNode event;

    @BeforeEach
    void setUp() {
        configurationService = mock(ConfigurationService.class);
        botService = mock(BitrixBotService.class);
        eventParser = mock(BitrixEventParser.class);
        textExtractor = mock(DocumentTextExtractor.class);
        bundleBuilder = mock(DocumentBundleBuilder.class);
        analysisService = mock(AnalysisService.class);
        messageChunker = mock(MessageChunker.class);
        event = mock(JsonNode.class);
        configuration = new BotConfiguration();

        when(configurationService.getInternalSnapshot()).thenReturn(configuration);
        when(eventParser.extractDialogId(event)).thenReturn("chat42");
        when(eventParser.extractChatDialogId(event)).thenReturn("chat42");
        when(eventParser.extractMessageText(event)).thenReturn("");

        processor = new BitrixMessageProcessor(
            configurationService,
            botService,
            eventParser,
            textExtractor,
            bundleBuilder,
            analysisService,
            messageChunker
        );
    }

    @Test
    void textOnlyMessageIsRejectedWithoutCallingLlm() {
        when(eventParser.extractAttachments(event)).thenReturn(List.of());

        processor.process(event);

        verify(botService).sendMessage(
            configuration.getBitrix(),
            "chat42",
            "Прикрепите файлы документов"
        );
        verify(analysisService, never()).analyze(any(), any(), any());
    }

    @Test
    void anyAttachmentWithExtractableTextIsSentToMainModel() {
        BitrixAttachment attachment = new BitrixAttachment(77L, "document.odt", 12L);
        byte[] content = "document text".getBytes(StandardCharsets.UTF_8);
        ExtractedDocument extracted = new ExtractedDocument("document.odt", "document text", false);

        when(eventParser.extractAttachments(event)).thenReturn(List.of(attachment));
        when(botService.downloadFile(configuration.getBitrix(), "chat42", "chat42", attachment))
            .thenReturn(new DownloadedBitrixFile(77L, "document.odt", 12L, content, "disk.file.get"));
        when(textExtractor.extract("document.odt", content, configuration.getMaxExtractedCharsPerFile()))
            .thenReturn(extracted);
        when(bundleBuilder.build(List.of(extracted), configuration.getMaxTotalExtractedChars()))
            .thenReturn("bundle");
        when(analysisService.analyze(configuration, "", "bundle")).thenReturn("answer");
        when(messageChunker.split("answer", configuration.getOutgoingMessageChunkSize()))
            .thenReturn(List.of("answer"));

        processor.process(event);

        verify(analysisService).analyze(configuration, "", "bundle");
        verify(botService).sendMessage(configuration.getBitrix(), "chat42", "answer");
    }

    @Test
    void passesUserCommentTogetherWithDocumentsToMainModel() {
        BitrixAttachment attachment = new BitrixAttachment(99L, "contract.pdf", 12L);
        byte[] content = "contract text".getBytes(StandardCharsets.UTF_8);
        ExtractedDocument extracted = new ExtractedDocument("contract.pdf", "contract text", false);

        when(eventParser.extractMessageText(event)).thenReturn("Я - арендодатель");
        when(eventParser.extractAttachments(event)).thenReturn(List.of(attachment));
        when(botService.downloadFile(configuration.getBitrix(), "chat42", "chat42", attachment))
            .thenReturn(new DownloadedBitrixFile(99L, "contract.pdf", 12L, content, "imbot.v2.File.download"));
        when(textExtractor.extract("contract.pdf", content, configuration.getMaxExtractedCharsPerFile()))
            .thenReturn(extracted);
        when(bundleBuilder.build(List.of(extracted), configuration.getMaxTotalExtractedChars()))
            .thenReturn("bundle");
        when(analysisService.analyze(configuration, "Я - арендодатель", "bundle")).thenReturn("answer");
        when(messageChunker.split("answer", configuration.getOutgoingMessageChunkSize()))
            .thenReturn(List.of("answer"));

        processor.process(event);

        verify(analysisService).analyze(configuration, "Я - арендодатель", "bundle");
    }

    @Test
    void attachmentWithoutExtractableTextDoesNotCallLlm() {
        BitrixAttachment attachment = new BitrixAttachment(88L, "picture.png", 10L);
        byte[] content = new byte[]{1, 2, 3};

        when(eventParser.extractAttachments(event)).thenReturn(List.of(attachment));
        when(botService.downloadFile(configuration.getBitrix(), "chat42", "chat42", attachment))
            .thenReturn(new DownloadedBitrixFile(88L, "picture.png", 10L, content, "disk.file.get"));
        when(textExtractor.extract(eq("picture.png"), eq(content), anyInt()))
            .thenReturn(new ExtractedDocument("picture.png", "", false));

        processor.process(event);

        verify(analysisService, never()).analyze(any(), any(), any());
        verify(botService).sendMessage(
            configuration.getBitrix(),
            "chat42",
            "Прикрепите файлы документов"
        );
    }
}
