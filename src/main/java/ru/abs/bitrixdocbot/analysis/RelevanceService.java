package ru.abs.bitrixdocbot.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.llm.LlmGateway;
import ru.abs.bitrixdocbot.logging.LogSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        log.info("RELEVANCE CHECK started userTextChars={} fileSummary={}",
            userText == null ? 0 : userText.length(),
            LogSanitizer.shortValue(fileSummary, 500));
        if (userText == null || userText.isBlank()) {
            RelevanceResult result = new RelevanceResult(true, "Документы отправлены без дополнительного запроса");
            log.info("RELEVANCE CHECK completed without model relevant=true reason={}", result.reason());
            return result;
        }
        if (!configuration.getSimpleModel().isConfigured()) {
            log.warn("RELEVANCE CHECK simple model is not configured; request is allowed by fail-open policy");
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
            log.debug("RELEVANCE CHECK raw classifier response chars={}", raw.length());
            RelevanceResult result = parse(raw);
            log.info("RELEVANCE CHECK completed relevant={} reason={}",
                result.relevant(), LogSanitizer.shortValue(result.reason(), 500));
            return result;
        } catch (Exception exception) {
            log.error("RELEVANCE CHECK failed; request is allowed by fail-open policy message={}",
                exception.getMessage(), exception);
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
