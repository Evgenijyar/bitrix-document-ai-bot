package ru.abs.bitrixdocbot.document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);
    private static final String START = "==================== EXTRACTED DOCUMENT TEXT START ====================";
    private static final String END = "==================== EXTRACTED DOCUMENT TEXT END ======================";

    private final Tika tika = new Tika();

    public ExtractedDocument extract(String fileName, byte[] content, int maxChars) {
        long started = System.nanoTime();
        int extractionLimit = maxChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxChars + 1;
        String detectedType = detectType(fileName, content);
        String sha256 = sha256(content);
        String magic = magic(content, 24);
        log.info("DOCUMENT EXTRACT -> fileName={} bytes={} detectedType={} sha256={} magic={} maxChars={}",
            fileName, content == null ? 0 : content.length, detectedType, sha256, magic, maxChars);

        validateRealDocument(fileName, detectedType, content);

        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            Metadata metadata = new Metadata();
            metadata.set("resourceName", fileName);
            String text = tika.parseToString(input, metadata, extractionLimit);
            String normalized = text == null ? "" : text.replace('\u0000', ' ').trim();
            boolean truncated = normalized.length() > maxChars;
            if (truncated) {
                normalized = normalized.substring(0, maxChars);
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info("DOCUMENT EXTRACT <- fileName={} extractedChars={} truncated={} durationMs={}",
                fileName, normalized.length(), truncated, durationMs);
            log.warn("{}\nFILE: {}\nDETECTED TYPE: {}\nSHA-256: {}\n{}\n{}",
                START, fileName, detectedType, sha256, normalized, END);
            return new ExtractedDocument(fileName, normalized, truncated);
        } catch (Exception exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.error("DOCUMENT EXTRACT !! fileName={} bytes={} detectedType={} sha256={} durationMs={} message={}",
                fileName, content == null ? 0 : content.length, detectedType, sha256,
                durationMs, exception.getMessage(), exception);
            throw new IllegalStateException("Cannot extract text from file " + fileName, exception);
        }
    }

    private String detectType(String fileName, byte[] content) {
        try {
            return tika.detect(content, fileName);
        } catch (Exception ignored) {
            return "application/octet-stream";
        }
    }

    private void validateRealDocument(String fileName, String detectedType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Downloaded file is empty: " + fileName);
        }
        String prefix = new String(content, 0, Math.min(content.length, 512), StandardCharsets.UTF_8)
            .stripLeading().toLowerCase();
        if (detectedType.contains("html") || prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) {
            throw new IllegalStateException(
                "Bitrix24 returned an HTML viewer page instead of the document bytes for " + fileName);
        }
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf") && !startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Downloaded content is not a PDF file: " + fileName);
        }
        if ((lower.endsWith(".docx") || lower.endsWith(".odt"))
            && !(content.length >= 2 && content[0] == 'P' && content[1] == 'K')) {
            throw new IllegalStateException("Downloaded content is not an OOXML/ZIP document: " + fileName);
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private String magic(byte[] content, int maxBytes) {
        if (content == null) return "";
        int length = Math.min(content.length, maxBytes);
        return HexFormat.ofDelimiter(" ").formatHex(content, 0, length);
    }
}
