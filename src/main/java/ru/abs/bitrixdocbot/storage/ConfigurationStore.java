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
import ru.abs.bitrixdocbot.domain.BotConfiguration;
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
            configuration = objectMapper.readValue(configPath.toFile(), BotConfiguration.class);
            log.info("CONFIG STORE existing configuration loaded path={} bytes={}",
                configPath.toAbsolutePath(), Files.size(configPath));
        } else {
            configuration = new BotConfiguration();
            persist(configuration);
            log.info("CONFIG STORE default configuration created path={}", configPath.toAbsolutePath());
        }
        normalize(configuration);
        log.info("CONFIG STORE initialization completed botId={} bitrixReady={} simpleConfigured={} complexConfigured={}",
            configuration.getBitrix().getBotId(),
            configuration.getBitrix().isReadyForPolling(),
            configuration.getSimpleModel().isConfigured(),
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
        if (value.getSimpleModel() == null) {
            value.setSimpleModel(new ru.abs.bitrixdocbot.domain.ModelSettings());
        }
        if (value.getComplexModel() == null) {
            value.setComplexModel(new ru.abs.bitrixdocbot.domain.ModelSettings());
        }
        if (value.getBitrix() == null) {
            value.setBitrix(new ru.abs.bitrixdocbot.domain.BitrixSettings());
        }
    }
}
