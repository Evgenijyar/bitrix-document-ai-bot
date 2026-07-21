package ru.abs.bitrixdocbot.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.abs.bitrixdocbot.admin.dto.BotConfigurationDto;
import ru.abs.bitrixdocbot.bitrix.BitrixBotService;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import ru.abs.bitrixdocbot.llm.LlmGateway;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ConfigurationService configurationService;
    private final BitrixBotService bitrixBotService;
    private final LlmGateway llmGateway;

    public AdminController(
        ConfigurationService configurationService,
        BitrixBotService bitrixBotService,
        LlmGateway llmGateway
    ) {
        this.configurationService = configurationService;
        this.bitrixBotService = bitrixBotService;
        this.llmGateway = llmGateway;
    }

    @GetMapping("/config")
    public BotConfigurationDto getConfiguration() {
        return configurationService.getForAdmin();
    }

    @PutMapping("/config")
    public BotConfigurationDto saveConfiguration(@Valid @RequestBody BotConfigurationDto dto) {
        return configurationService.saveFromAdmin(dto);
    }

    @PostMapping("/models/test")
    public Map<String, Object> testModel(@RequestParam String target) {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        ModelSettings settings = switch (target.toLowerCase()) {
            case "simple" -> configuration.getSimpleModel();
            case "complex" -> configuration.getComplexModel();
            default -> throw new IllegalArgumentException("target must be simple or complex");
        };
        String response = llmGateway.test(settings);
        return Map.of("ok", true, "response", response);
    }

    @PostMapping("/bitrix/register")
    public JsonNode registerBot() {
        return bitrixBotService.registerBot();
    }

    @PostMapping("/bitrix/check")
    public JsonNode checkBot() {
        return bitrixBotService.checkBot();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simpleModelConfigured", configuration.getSimpleModel().isConfigured());
        result.put("complexModelConfigured", configuration.getComplexModel().isConfigured());
        result.put("bitrixWebhookConfigured", configuration.getBitrix().getWebhookUrl() != null
            && !configuration.getBitrix().getWebhookUrl().isBlank());
        result.put("botRegistered", configuration.getBitrix().getBotId() != null);
        result.put("botId", configuration.getBitrix().getBotId());
        result.put("eventOffset", configuration.getBitrix().getEventOffset());
        return result;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of(
            "ok", false,
            "error", exception.getClass().getSimpleName(),
            "message", exception.getMessage() == null ? "Unknown error" : exception.getMessage()
        ));
    }
}
