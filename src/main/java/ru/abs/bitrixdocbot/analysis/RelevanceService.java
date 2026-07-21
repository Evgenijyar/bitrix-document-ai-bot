package ru.abs.bitrixdocbot.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.llm.LlmGateway;

@Service
public class RelevanceService {

    private static final Logger log = LoggerFactory.getLogger(RelevanceService.class);

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public RelevanceService(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public RelevanceResult check(BotConfiguration configuration, String userText, String fileSummary) {
        if (userText == null || userText.isBlank()) {
            return new RelevanceResult(true, "Документы отправлены без дополнительного запроса");
        }
        if (!configuration.getSimpleModel().isConfigured()) {
            log.warn("Simple model is not configured; request is allowed by fail-open policy");
            return new RelevanceResult(true, "Классификатор не настроен");
        }

        String prompt = """
            Сообщение пользователя:
            %s

            Приложенные файлы:
            %s
            """.formatted(userText, fileSummary);

        try {
            String raw = llmGateway.generate(configuration.getSimpleModel(), configuration.getRelevancePrompt(), prompt);
            return parse(raw);
        } catch (Exception exception) {
            log.warn("Relevance classifier failed; request is allowed by fail-open policy: {}", exception.getMessage());
            return new RelevanceResult(true, "Классификатор временно недоступен");
        }
    }

    private RelevanceResult parse(String raw) throws Exception {
        String cleaned = raw.trim()
            .replaceFirst("^```json\\s*", "")
            .replaceFirst("^```\\s*", "")
            .replaceFirst("\\s*```$", "")
            .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        JsonNode json = objectMapper.readTree(cleaned);
        return new RelevanceResult(json.path("relevant").asBoolean(false), json.path("reason").asString(""));
    }
}
