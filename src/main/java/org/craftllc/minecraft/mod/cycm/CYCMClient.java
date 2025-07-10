package org.craftllc.minecraft.mod.cycm; // Правильний пакет для чисто клієнтського класу

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.craftllc.minecraft.mod.cycm.config.ModConfig; // Правильний імпорт конфігурації

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CYCMClient implements ClientModInitializer {
    // MOD_ID та LOGGER тепер знаходяться в Constants
    // public static final String MOD_ID = "cycm";
    // public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Path MOD_CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(Constants.MOD_ID);
    private static final Path COMMANDS_FILE = MOD_CONFIG_DIR.resolve("commands_list.txt");
    private static final Path CHAT_FILE = MOD_CONFIG_DIR.resolve("chat.txt");

    private static int lastReadCommandsLineCount = 0;
    private static int lastReadChatLineCount = 0;

    private static ScheduledExecutorService scheduler;
    public static ModConfig config = ModConfig.load();

    @Override
    public void onInitializeClient() { // Метод для ClientModInitializer
        Constants.LOGGER.info("CraftLLC YouTube Command Mod: Ініціалізація...");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && config.isModEnabled()) {
                if (scheduler == null || scheduler.isShutdown()) {
                    Constants.LOGGER.info("Мод увімкнено. Починаємо прослуховувати зміни в файлах...");
                    startFileProcessing();
                }
            } else {
                stopFileProcessing();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null && scheduler != null && !scheduler.isShutdown()) {
                Constants.LOGGER.info("Гравець вийшов з світу/сервера. Вимикаємо прослуховування...");
                stopFileProcessing();
            }
        });
    }

    private void startFileProcessing() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> processFiles(), 0, 2, TimeUnit.SECONDS);
    }

    private void stopFileProcessing() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public static void setModEnabled(boolean enabled) {
        config.setModEnabled(enabled);
        ModConfig.save(config);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            if (enabled) {
                // Створення нового екземпляра CYCMClient для виклику нестатичних методів.
                // Можливо, краще було б передавати посилання на поточний екземпляр або зробити методи статичними,
                // якщо вони не залежать від стану екземпляра.
                // Наразі залишаємо так, як було, але звертаємо увагу на цей момент.
                new CYCMClient().startFileProcessing();
            } else {
                new CYCMClient().stopFileProcessing();
            }
        }
    }

    private void processFiles() {
        if (!config.isModEnabled()) {
            stopFileProcessing();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            processCommandsFile(client);
            processChatFile(client);
        } else {
            stopFileProcessing();
        }
    }

    private void processCommandsFile(MinecraftClient client) {
        try {
            if (!Files.exists(COMMANDS_FILE)) {
                Files.createDirectories(COMMANDS_FILE.getParent());
                Files.createFile(COMMANDS_FILE);
                Constants.LOGGER.info("Створено commands_list.txt в {}", COMMANDS_FILE);
                return;
            }

            List<String> lines = Files.readAllLines(COMMANDS_FILE);
            if (lines.size() > lastReadCommandsLineCount) {
                for (int i = lastReadCommandsLineCount; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.matches("^[a-zA-Z0-9_]+:/.*")) {
                        String[] parts = line.split(":", 2);
                        String nickname = parts[0];
                        String command = parts[1].substring(1);

                        MutableText message = Text.literal("§f" + nickname + " ")
                                .append(Text.literal("§6ввів команду "))
                                .append(Text.literal("§f'/" + command + "'"));
                        client.player.sendMessage(message, false);

                        client.player.sendChatMessage("/" + command);
                    }
                }
                lastReadCommandsLineCount = lines.size();
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка читання commands_list.txt: {}", e.getMessage());
        }
    }

    private void processChatFile(MinecraftClient client) {
        try {
            if (!Files.exists(CHAT_FILE)) {
                Files.createDirectories(CHAT_FILE.getParent());
                Files.createFile(CHAT_FILE);
                Constants.LOGGER.info("Створено chat.txt at {}", CHAT_FILE);
                return;
            }

            List<String> lines = Files.readAllLines(CHAT_FILE);
            if (lines.size() > lastReadChatLineCount) {
                for (int i = lastReadChatLineCount; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.matches("^[a-zA-Z0-9_]+:.*")) {
                        String[] parts = line.split(":", 2);
                        String nickname = parts[0];
                        String messageContent = parts[1];

                        MutableText chatMessage = Text.literal("<")
                                .append(Text.literal(nickname).formatted(Formatting.WHITE))
                                .append(Text.literal("> "))
                                .append(Text.literal(messageContent));
                        client.player.sendMessage(chatMessage, false);
                    }
                }
                lastReadChatLineCount = lines.size();
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка читання chat.txt: {}", e.getMessage());
        }
    }
}