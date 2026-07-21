package ru.abs.bitrixdocbot.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.config.ApplicationProperties;
import ru.abs.bitrixdocbot.domain.BotConfiguration;

@Service
public class ConfigurationStore {

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
        Files.createDirectories(configPath.getParent());
        if (Files.exists(configPath)) {
            configuration = objectMapper.readValue(configPath.toFile(), BotConfiguration.class);
        } else {
            configuration = new BotConfiguration();
            persist(configuration);
        }
        normalize(configuration);
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
        try {
            Path tempPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), value);
            try {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveNotSupported) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
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
