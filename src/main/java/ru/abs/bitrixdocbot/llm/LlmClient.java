package ru.abs.bitrixdocbot.llm;

import ru.abs.bitrixdocbot.domain.ModelSettings;

public interface LlmClient {
    String generate(ModelSettings settings, String systemPrompt, String userPrompt);
}
