package org.craftllc.minecraft.mod.cycm.config;

// Клас, що представляє структуру JSON-файлу конфігурації
public class ModConfig {

    private boolean modEnabled = true; // Приклад властивості: чи увімкнено мод

    // Додай інші властивості, які ти хочеш зберігати в конфігурації, наприклад:
    // private String commandPrefix = "/";
    // private int someNumericValue = 10;

    public ModConfig() {
        // Конструктор за замовчуванням для Gson
    }

    // Геттери та сеттери для властивостей
    public boolean isModEnabled() {
        return modEnabled;
    }

    public void setModEnabled(boolean modEnabled) {
        this.modEnabled = modEnabled;
    }

    // Додай геттери/сеттери для інших твоїх властивостей
    // public String getCommandPrefix() { return commandPrefix; }
    // public void setCommandPrefix(String commandPrefix) { this.commandPrefix = commandPrefix; }
}
