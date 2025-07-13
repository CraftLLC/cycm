package org.craftllc.minecraft.mod.cycm.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ModConfigManager {
    private static final Logger LOGGER = LogManager.getLogger("CYCMConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("cycm.json");

    private static ModConfigManager INSTANCE; // Синглтон
    private ModConfig config;
    private long lastModified = 0L; // Для відстеження змін у файлі
    private ScheduledExecutorService scheduler; // Для фонової перевірки файлу

    private ModConfigManager() {
        loadConfig(); // Завантажуємо конфігурацію при створенні менеджера
    }

    public static ModConfigManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfigManager();
        }
        return INSTANCE;
    }

    public ModConfig getConfig() {
        return config;
    }

    // Метод для завантаження конфігурації з файлу
    public void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                // Оновлюємо lastModified перед читанням
                lastModified = Files.getLastModifiedTime(CONFIG_PATH).toMillis();
                try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                    config = GSON.fromJson(reader, ModConfig.class);
                    if (config == null) { // Якщо файл порожній або пошкоджений
                        config = new ModConfig();
                        saveConfig(); // Зберігаємо дефолтну конфігурацію
                    }
                }
                LOGGER.info("Config loaded from: " + CONFIG_PATH);
            } else {
                config = new ModConfig(); // Створюємо нову конфігурацію, якщо файлу немає
                saveConfig(); // І зберігаємо її
                LOGGER.info("Config file created at: " + CONFIG_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load config from " + CONFIG_PATH, e);
            config = new ModConfig(); // У разі помилки завантажуємо дефолтну
        }
    }

    // Метод для збереження конфігурації в файл
    public void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(config, writer);
            // Оновлюємо lastModified після запису
            lastModified = Files.getLastModifiedTime(CONFIG_PATH).toMillis();
            LOGGER.info("Config saved to: " + CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save config to " + CONFIG_PATH, e);
        }
    }

    // Запускаємо фонову перевірку файлу
    public void startWatchingConfigFile() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            // Перевіряємо файл кожні 5 секунд
            scheduler.scheduleAtFixedRate(this::checkForConfigChanges, 5, 5, TimeUnit.SECONDS);
            LOGGER.info("Started watching config file: " + CONFIG_PATH);
        }
    }

    // Зупиняємо фонову перевірку файлу (при вимкненні мода/гри)
    public void stopWatchingConfigFile() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            LOGGER.info("Stopped watching config file: " + CONFIG_PATH);
        }
    }

    // Метод для перевірки змін у файлі
    private void checkForConfigChanges() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                long currentModified = Files.getLastModifiedTime(CONFIG_PATH).toMillis();
                if (currentModified > lastModified) {
                    LOGGER.info("Config file changed. Reloading config...");
                    loadConfig(); // Перезавантажуємо конфігурацію
                    // Тут ти можеш додати логіку, яка реагує на зміну конфігурації
                    // Наприклад, оновити стан в CYCMClient.
                    // CYCMClient.setModEnabled(config.isModEnabled()); // Це буде зроблено в CYCMClient
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error checking config file for changes: " + CONFIG_PATH, e);
        }
    }
}
