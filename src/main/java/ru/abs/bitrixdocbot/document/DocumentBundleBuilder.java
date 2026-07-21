package ru.abs.bitrixdocbot.document;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class DocumentBundleBuilder {

    public String build(List<ExtractedDocument> documents, int maxTotalChars) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            ExtractedDocument document = documents.get(index);
            String header = "\n\n--- ДОКУМЕНТ %d: %s%s ---\n".formatted(
                index + 1,
                document.fileName(),
                document.truncated() ? " [текст файла обрезан по лимиту]" : ""
            );
            if (result.length() + header.length() >= maxTotalChars) {
                break;
            }
            result.append(header);
            int remaining = maxTotalChars - result.length();
            if (document.text().length() > remaining) {
                result.append(document.text(), 0, remaining);
                result.append("\n[Общий текст комплекта обрезан по лимиту]");
                break;
            }
            result.append(document.text());
        }
        return result.toString().trim();
    }
}
