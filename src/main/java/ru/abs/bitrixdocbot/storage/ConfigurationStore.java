package ru.abs.bitrixdocbot.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.config.ApplicationProperties;
import ru.abs.bitrixdocbot.domain.BitrixSettings;
import ru.abs.bitrixdocbot.domain.BotConfiguration;
import ru.abs.bitrixdocbot.domain.ModelSettings;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConfigurationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationStore.class);

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private BotConfiguration configuration;

    public ConfigurationStore(ObjectMapper objectMapper, ApplicationProperties properties) {
        this.objectMapper = objectMapper;
        this.configPath = Path.of(properties.getDataDir()).resolve("config.json");
    }

    @PostConstruct
    void initialize() throws IOException {
        log.info("CONFIG STORE initialization started path={}", configPath.toAbsolutePath());
        Files.createDirectories(configPath.getParent());
        if (Files.exists(configPath)) {
            String rawConfiguration = Files.readString(configPath);
            boolean legacyClassifierFieldsPresent = containsLegacyClassifierFields(rawConfiguration);
            configuration = objectMapper.readValue(rawConfiguration, BotConfiguration.class);
            log.info("CONFIG STORE existing configuration loaded path={} bytes={} classifierFieldsMigrated={}",
                configPath.toAbsolutePath(), Files.size(configPath), legacyClassifierFieldsPresent);
            normalize(configuration);
            if (legacyClassifierFieldsPresent) {
                persist(configuration);
                log.info("CONFIG STORE legacy classifier configuration removed from persisted config");
            }
        } else {
            configuration = new BotConfiguration();
            persist(configuration);
            log.info("CONFIG STORE default configuration created path={}", configPath.toAbsolutePath());
        }
        normalize(configuration);
        log.info("CONFIG STORE initialization completed botId={} bitrixReady={} complexConfigured={}",
            configuration.getBitrix().getBotId(),
            configuration.getBitrix().isReadyForPolling(),
            configuration.getComplexModel().isConfigured());
    }

    public BotConfiguration getSnapshot() {
        lock.readLock().lock();
        try {
            return copy(configuration);
        } finally {
            lock.readLock().unlock();
        }
    }

    public BotConfiguration replace(BotConfiguration newConfiguration) {
        lock.writeLock().lock();
        try {
            normalize(newConfiguration);
            persist(newConfiguration);
            configuration = copy(newConfiguration);
            return copy(configuration);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public BotConfiguration update(Consumer<BotConfiguration> updater) {
        lock.writeLock().lock();
        try {
            BotConfiguration updated = copy(configuration);
            updater.accept(updated);
            normalize(updated);
            persist(updated);
            configuration = updated;
            return copy(configuration);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean containsLegacyClassifierFields(String json) {
        return json.contains("\"simpleModel\"")
            || json.contains("\"relevancePrompt\"")
            || json.contains("\"irrelevantReply\"");
    }

    private void persist(BotConfiguration value) {
        long started = System.nanoTime();
        try {
            Path tempPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), value);
            try {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveNotSupported) {
                log.debug("CONFIG STORE atomic move unavailable; using regular replace path={}", configPath.toAbsolutePath());
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.debug("CONFIG STORE persisted path={} bytes={} durationMs={}",
                configPath.toAbsolutePath(), Files.size(configPath), durationMs);
        } catch (IOException exception) {
            log.error("CONFIG STORE persistence failed path={}", configPath.toAbsolutePath(), exception);
            throw new IllegalStateException("Cannot save configuration to " + configPath, exception);
        }
    }

    private BotConfiguration copy(BotConfiguration source) {
        return objectMapper.convertValue(source, BotConfiguration.class);
    }

    private void normalize(BotConfiguration value) {
        if (value.getComplexModel() == null) {
            value.setComplexModel(new ModelSettings());
        }
        if (value.getBitrix() == null) {
            value.setBitrix(new BitrixSettings());
        }
        if (value.getAnalysisPrompt() == null) {
            value.setAnalysisPrompt("");
        }
        if (value.getNoFilesReply() == null || value.getNoFilesReply().isBlank()) {
            value.setNoFilesReply("Прикрепите файлы документов");
        }
        if (value.getProcessingReply() == null) {
            value.setProcessingReply("");
        }
        if (value.getErrorReply() == null) {
            value.setErrorReply("");
        }
    }
}
