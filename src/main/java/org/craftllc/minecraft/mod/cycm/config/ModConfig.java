package org.craftllc.minecraft.mod.cycm.config; // Правильний пакет

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.craftllc.minecraft.mod.cycm.Constants;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config/" + Constants.MOD_ID + ".json"); // Використовуємо Constants.MOD_ID

    private boolean modEnabled = true;

    public boolean isModEnabled() {
        return modEnabled;
    }

    public void setModEnabled(boolean modEnabled) {
        this.modEnabled = modEnabled;
    }

    public static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                return GSON.fromJson(reader, ModConfig.class);
            } catch (IOException e) {
                Constants.LOGGER.error("Помилка читання конфігурації з {}", CONFIG_PATH, e); // Використовуємо Constants.LOGGER
            }
        }
        ModConfig defaultConfig = new ModConfig();
        save(defaultConfig);
        return defaultConfig;
    }

    public static void save(ModConfig config) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка збереження конфігурації в {}", CONFIG_PATH, e); // Використовуємо Constants.LOGGER
        }
    }
}