package ru.abs.bitrixdocbot.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageChunkerTest {

    private final MessageChunker chunker = new MessageChunker();

    @Test
    void keepsShortMessageAsIs() {
        assertThat(chunker.split("Короткий ответ", 100)).containsExactly("Короткий ответ");
    }

    @Test
    void splitsAndNumbersLongMessage() {
        var chunks = chunker.split("Первый абзац.\n\nВторой абзац.\n\nТретий абзац.", 20);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst()).startsWith("Часть 1/");
    }
}
