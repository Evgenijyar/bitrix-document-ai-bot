package ru.abs.bitrixdocbot.document;

import java.io.ByteArrayInputStream;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Service;

@Service
public class DocumentTextExtractor {

    private final Tika tika = new Tika();

    public ExtractedDocument extract(String fileName, byte[] content, int maxChars) {
        int extractionLimit = maxChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxChars + 1;
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            String text = tika.parseToString(input, new Metadata(), extractionLimit);
            String normalized = text == null ? "" : text.replace('\u0000', ' ').trim();
            boolean truncated = normalized.length() > maxChars;
            if (truncated) {
                normalized = normalized.substring(0, maxChars);
            }
            return new ExtractedDocument(fileName, normalized, truncated);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot extract text from file " + fileName, exception);
        }
    }
}
