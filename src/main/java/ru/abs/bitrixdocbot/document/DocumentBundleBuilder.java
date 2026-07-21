package ru.abs.bitrixdocbot.document;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(DocumentBundleBuilder.class);

    public String build(List<ExtractedDocument> documents, int maxTotalChars) {
        log.info("DOCUMENT BUNDLE build started documents={} maxTotalChars={}", documents.size(), maxTotalChars);
        StringBuilder result = new StringBuilder();
        int includedDocuments = 0;
        boolean totalTruncated = false;
        for (int index = 0; index < documents.size(); index++) {
            ExtractedDocument document = documents.get(index);
            String header = "\n\n--- ДОКУМЕНТ %d: %s%s ---\n".formatted(
                index + 1,
                document.fileName(),
                document.truncated() ? " [текст файла обрезан по лимиту]" : ""
            );
            if (result.length() + header.length() >= maxTotalChars) {
                totalTruncated = true;
                break;
            }
            result.append(header);
            includedDocuments++;
            int remaining = maxTotalChars - result.length();
            if (document.text().length() > remaining) {
                result.append(document.text(), 0, remaining);
                result.append("\n[Общий текст комплекта обрезан по лимиту]");
                totalTruncated = true;
                break;
            }
            result.append(document.text());
        }
        String bundle = result.toString().trim();
        log.info("DOCUMENT BUNDLE build completed includedDocuments={} bundleChars={} totalTruncated={}",
            includedDocuments, bundle.length(), totalTruncated);
        return bundle;
    }
}
