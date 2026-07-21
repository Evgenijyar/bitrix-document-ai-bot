package ru.abs.bitrixdocbot.bitrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import ru.abs.bitrixdocbot.message.MessageChunker;
import tools.jackson.databind.JsonNode;

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
        long started = System.nanoTime();
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        String dialogId = eventParser.extractDialogId(data);
        if (dialogId.isBlank()) {
            log.warn("MESSAGE PROCESSOR cannot determine dialogId event={}", LogSanitizer.sanitizeJson(data));
            return;
        }

        String userText = eventParser.extractMessageText(data);
        List<BitrixAttachment> attachments = eventParser.extractAttachments(data);
        List<BitrixAttachment> supported = attachments.stream().filter(this::isSupported).toList();

        log.info("MESSAGE PROCESSOR started dialogId={} userTextChars={} attachments={} supported={} files={}",
            dialogId,
            userText == null ? 0 : userText.length(),
            attachments.size(),
            supported.size(),
            supported.stream().map(attachment -> attachment.fileName() + "#" + attachment.fileId())
                .collect(Collectors.joining(", ")));

        if (supported.isEmpty()) {
            log.info("MESSAGE PROCESSOR rejected dialogId={} reason=no-supported-files", dialogId);
            botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getIrrelevantReply());
            return;
        }
        if (supported.size() > configuration.getMaxFileCount()) {
            log.info("MESSAGE PROCESSOR rejected dialogId={} reason=too-many-files count={} limit={}",
                dialogId, supported.size(), configuration.getMaxFileCount());
            botService.sendMessage(configuration.getBitrix(), dialogId,
                "Слишком много файлов. Максимум: " + configuration.getMaxFileCount() + ".");
            return;
        }

        try {
            String fileSummary = supported.stream()
                .map(BitrixAttachment::fileName)
                .collect(Collectors.joining(", "));
            log.info("MESSAGE PROCESSOR relevance check started dialogId={} files={}", dialogId, fileSummary);
            RelevanceResult relevance = relevanceService.check(configuration, userText, fileSummary);
            log.info("MESSAGE PROCESSOR relevance check completed dialogId={} relevant={} reason={}",
                dialogId, relevance.relevant(), LogSanitizer.shortValue(relevance.reason(), 300));
            if (!relevance.relevant()) {
                botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getIrrelevantReply());
                return;
            }

            botService.sendMessage(configuration.getBitrix(), dialogId, configuration.getProcessingReply());

            List<ExtractedDocument> documents = new ArrayList<>();
            for (BitrixAttachment attachment : supported) {
                log.info("MESSAGE PROCESSOR file started dialogId={} fileId={} fileName={} declaredBytes={}",
                    dialogId, attachment.fileId(), attachment.fileName(), attachment.declaredSize());
                if (attachment.declaredSize() != null
                    && attachment.declaredSize() > configuration.getMaxFileSizeBytes()) {
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
                log.info("MESSAGE PROCESSOR file completed dialogId={} fileId={} fileName={} downloadedBytes={} "
                        + "extractedChars={} truncated={}",
                    dialogId,
                    attachment.fileId(),
                    attachment.fileName(),
                    content.length,
                    document.text().length(),
                    document.truncated());
            }

            String documentBundle = bundleBuilder.build(documents, configuration.getMaxTotalExtractedChars());
            log.info("MESSAGE PROCESSOR analysis started dialogId={} documents={} bundleChars={}",
                dialogId, documents.size(), documentBundle.length());
            String answer = analysisService.analyze(configuration, userText, documentBundle);
            List<String> chunks = messageChunker.split(answer, configuration.getOutgoingMessageChunkSize());
            log.info("MESSAGE PROCESSOR analysis completed dialogId={} answerChars={} outgoingChunks={}",
                dialogId, answer.length(), chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                log.info("MESSAGE PROCESSOR sending result dialogId={} chunk={}/{} chars={}",
                    dialogId, index + 1, chunks.size(), chunk.length());
                botService.sendMessage(configuration.getBitrix(), dialogId, chunk);
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("MESSAGE PROCESSOR completed dialogId={} durationMs={}", dialogId, durationMs);
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("MESSAGE PROCESSOR failed dialogId={} durationMs={} message={}",
                dialogId, durationMs, exception.getMessage(), exception);
            try {
                botService.sendMessage(configuration.getBitrix(), dialogId,
                    configuration.getErrorReply() + "\n\nТехническая причина: " + safeMessage(exception));
            } catch (Exception sendException) {
                log.error("MESSAGE PROCESSOR could not send error reply dialogId={} message={}",
                    dialogId, sendException.getMessage(), sendException);
            }
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
