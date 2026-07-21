package ru.abs.bitrixdocbot.bitrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.admin.ConfigurationService;
import ru.abs.bitrixdocbot.analysis.AnalysisService;
import ru.abs.bitrixdocbot.analysis.RelevanceResult;
import ru.abs.bitrixdocbot.analysis.RelevanceService;
import ru.abs.bitrixdocbot.document.BitrixAttachment;
import ru.abs.bitrixdocbot.document.DocumentBundleBuilder;
import ru.abs.bitrixdocbot.document.DocumentTextExtractor;
import ru.abs.bitrixdocbot.document.ExtractedDocument;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.message.MessageChunker;

@Service
public class BitrixMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(BitrixMessageProcessor.class);

    private final ConfigurationService configurationService;
    private final BitrixBotService botService;
    private final BitrixEventParser eventParser;
    private final DocumentTextExtractor textExtractor;
    private final DocumentBundleBuilder bundleBuilder;
    private final RelevanceService relevanceService;
    private final AnalysisService analysisService;
    private final MessageChunker messageChunker;

    public BitrixMessageProcessor(
        ConfigurationService configurationService,
        BitrixBotService botService,
        BitrixEventParser eventParser,
        DocumentTextExtractor textExtractor,
        DocumentBundleBuilder bundleBuilder,
        RelevanceService relevanceService,
        AnalysisService analysisService,
        MessageChunker messageChunker
    ) {
        this.configurationService = configurationService;
        this.botService = botService;
        this.eventParser = eventParser;
        this.textExtractor = textExtractor;
        this.bundleBuilder = bundleBuilder;
        this.relevanceService = relevanceService;
        this.analysisService = analysisService;
        this.messageChunker = messageChunker;
    }

    public void process(JsonNode data) {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        String dialogId = eventParser.extractDialogId(data);
        if (dialogId.isBlank()) {
            log.warn("Cannot determine dialogId for event: {}", data);
            return;
        }

        String userText = eventParser.extractMessageText(data);
        List<BitrixAttachment> attachments = eventParser.extractAttachments(data);
        List<BitrixAttachment> supported = attachments.stream().filter(this::isSupported).toList();

        if (supported.isEmpty()) {
            botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getIrrelevantReply());
            return;
        }
        if (supported.size() > configuration.getMaxFileCount()) {
            botService.sendMessage(configuration.getBitrix(), dialogId,
                "Слишком много файлов. Максимум: " + configuration.getMaxFileCount() + ".");
            return;
        }

        try {
            String fileSummary = supported.stream()
                .map(BitrixAttachment::fileName)
                .collect(Collectors.joining(", "));
            RelevanceResult relevance = relevanceService.check(configuration, userText, fileSummary);
            if (!relevance.relevant()) {
                botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getIrrelevantReply());
                return;
            }

            botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getProcessingReply());

            List<ExtractedDocument> documents = new ArrayList<>();
            for (BitrixAttachment attachment : supported) {
                if (attachment.declaredSize() != null && attachment.declaredSize() > configuration.getMaxFileSizeBytes()) {
                    throw new IllegalArgumentException("Файл слишком большой: " + attachment.fileName());
                }
                byte[] content = botService.downloadFile(configuration.getBitrix(), attachment.fileId());
                if (content.length > configuration.getMaxFileSizeBytes()) {
                    throw new IllegalArgumentException("Файл слишком большой: " + attachment.fileName());
                }
                ExtractedDocument document = textExtractor.extract(
                    attachment.fileName(), content, configuration.getMaxExtractedCharsPerFile());
                if (document.text().isBlank()) {
                    throw new IllegalArgumentException("Не удалось извлечь текст из файла: " + attachment.fileName());
                }
                documents.add(document);
            }

            String documentBundle = bundleBuilder.build(documents, configuration.getMaxTotalExtractedChars());
            String answer = analysisService.analyze(configuration, userText, documentBundle);
            for (String chunk : messageChunker.split(answer, configuration.getOutgoingMessageChunkSize())) {
                botService.sendMessage(configuration.getBitrix(), dialogId, chunk);
            }
        } catch (Exception exception) {
            log.error("Document analysis failed for dialog {}", dialogId, exception);
            botService.sendMessage(configuration.getBitrix(), dialogId,
                configuration.getErrorReply() + "\n\nТехническая причина: " + safeMessage(exception));
        }
    }

    private boolean isSupported(BitrixAttachment attachment) {
        String name = attachment.fileName().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
