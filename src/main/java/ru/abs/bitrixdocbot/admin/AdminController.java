package ru.abs.bitrixdocbot.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs.bitrixdocbot.admin.dto.BotConfigurationDto;
import ru.abs.bitrixdocbot.bitrix.BitrixBotService;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.llm.LlmGateway;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

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
        log.info("ADMIN CONFIG read requested");
        BotConfigurationDto result = configurationService.getForAdmin();
        log.info("ADMIN CONFIG read completed");
        return result;
    }

    @PutMapping("/config")
    public BotConfigurationDto saveConfiguration(@Valid @RequestBody BotConfigurationDto dto) {
        log.info("ADMIN CONFIG save requested");
        BotConfigurationDto result = configurationService.saveFromAdmin(dto);
        log.info("ADMIN CONFIG save completed");
        return result;
    }

    @PostMapping({"/model/test", "/models/test"})
    public Map<String, Object> testModel() {
        log.info("ADMIN MODEL TEST requested target=complex");
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        String response = llmGateway.test(configuration.getComplexModel());
        log.info("ADMIN MODEL TEST completed target=complex responseChars={}", response.length());
        return Map.of("ok", true, "response", response);
    }

    @PostMapping("/bitrix/register")
    public JsonNode registerBot() {
        log.info("ADMIN BITRIX REGISTER button operation started");
        JsonNode result = bitrixBotService.registerBot();
        log.info("ADMIN BITRIX REGISTER button operation completed");
        return result;
    }

    @PostMapping("/bitrix/check")
    public JsonNode checkBot() {
        log.info("ADMIN BITRIX CHECK button operation started");
        JsonNode result = bitrixBotService.checkBot();
        log.info("ADMIN BITRIX CHECK button operation completed");
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        log.debug("ADMIN STATUS requested");
        BotConfiguration configuration = configurationService.getInternalSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complexModelConfigured", configuration.getComplexModel().isConfigured());
        result.put("bitrixWebhookConfigured", configuration.getBitrix().getWebhookUrl() != null
            && !configuration.getBitrix().getWebhookUrl().isBlank());
        result.put("botRegistered", configuration.getBitrix().getBotId() != null);
        result.put("botId", configuration.getBitrix().getBotId());
        result.put("eventOffset", configuration.getBitrix().getEventOffset());
        log.debug("ADMIN STATUS completed complexConfigured={} botRegistered={} botId={} offset={}",
            result.get("complexModelConfigured"),
            result.get("botRegistered"),
            result.get("botId"),
            result.get("eventOffset"));
        return result;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception exception, HttpServletRequest request) {
        log.error("ADMIN OPERATION failed method={} uri={} exception={} message={}",
            request.getMethod(),
            request.getRequestURI(),
            exception.getClass().getSimpleName(),
            exception.getMessage(),
            exception);
        return ResponseEntity.badRequest().body(Map.of(
            "ok", false,
            "error", exception.getClass().getSimpleName(),
            "message", exception.getMessage() == null ? "Unknown error" : exception.getMessage()
        ));
    }
}
