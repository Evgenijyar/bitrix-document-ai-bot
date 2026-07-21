package ru.abs.bitrixdocbot.message;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class MessageChunker {

    public List<String> split(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return List.of("Модель вернула пустой ответ.");
        }
        if (text.length() <= maxLength) {
            return List.of(text.trim());
        }

        List<String> chunks = new ArrayList<>();
        String remaining = text.trim();
        while (!remaining.isEmpty()) {
            int end = Math.min(maxLength, remaining.length());
            if (end < remaining.length()) {
                int paragraph = remaining.lastIndexOf("\n\n", end);
                int newline = remaining.lastIndexOf('\n', end);
                int sentence = remaining.lastIndexOf(". ", end);
                int space = remaining.lastIndexOf(' ', end);
                end = bestSplit(paragraph, newline, sentence >= 0 ? sentence + 1 : -1, space, end);
            }
            String chunk = remaining.substring(0, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            remaining = remaining.substring(end).trim();
        }

        if (chunks.size() == 1) {
            return chunks;
        }
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            numbered.add("Часть %d/%d\n\n%s".formatted(i + 1, chunks.size(), chunks.get(i)));
        }
        return numbered;
    }

    private int bestSplit(int paragraph, int newline, int sentence, int space, int fallback) {
        int threshold = fallback / 2;
        if (paragraph >= threshold) return paragraph;
        if (newline >= threshold) return newline;
        if (sentence >= threshold) return sentence;
        if (space >= threshold) return space;
        return fallback;
    }
}
