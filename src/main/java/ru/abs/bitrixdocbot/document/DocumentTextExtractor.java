package ru.abs.bitrixdocbot.document;

import java.io.ByteArrayInputStream;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    private final Tika tika = new Tika();

    public ExtractedDocument extract(String fileName, byte[] content, int maxChars) {
        long started = System.nanoTime();
        int extractionLimit = maxChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxChars + 1;
        log.info("DOCUMENT EXTRACT -> fileName={} bytes={} maxChars={}",
            fileName, content == null ? 0 : content.length, maxChars);
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            String text = tika.parseToString(input, new Metadata(), extractionLimit);
            String normalized = text == null ? "" : text.replace('\u0000', ' ').trim();
            boolean truncated = normalized.length() > maxChars;
            if (truncated) {
                normalized = normalized.substring(0, maxChars);
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("DOCUMENT EXTRACT <- fileName={} extractedChars={} truncated={} durationMs={}",
                fileName, normalized.length(), truncated, durationMs);
            return new ExtractedDocument(fileName, normalized, truncated);
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("DOCUMENT EXTRACT !! fileName={} bytes={} durationMs={} message={}",
                fileName, content == null ? 0 : content.length, durationMs, exception.getMessage(), exception);
            throw new IllegalStateException("Cannot extract text from file " + fileName, exception);
        }
    }
}
