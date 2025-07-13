package org.craftllc.minecraft.mod.cycm.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonObject; // Важливо додати
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.craftllc.minecraft.mod.cycm.CYCMClient;
import org.craftllc.minecraft.mod.cycm.Constants;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AIClient {
    private static final String GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=";

    private static String apiKey = null;
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create(); // Ensure no HTML escaping
    private static final List<AIConversationEntry> conversationHistory = Collections.synchronizedList(new CopyOnWriteArrayList<>());
    private static ScheduledExecutorService stopAiScheduler;
    public static String lastExecutedCommandOutput = null; // Store output of the last executed command, public for CYCMClient

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^\\s*/([a-zA-Z0-9_]+)");

    // AIClient не є ClientModInitializer, тому цей метод видалено.
    // Його ініціалізація відбувається через CYCMClient.onInitializeClient().

    public static void loadApiKey() {
            // Передаємо екземпляр CYCMClient, щоб отримати доступ до configManager
            // або, краще, зробимо configManager публічним статичним полем у CYCMClient
            // щоб до нього можна було звертатись напряму.

            // Цей рядок викликає помилку, тому що configManager може бути не ініціалізований
            // private static final Path API_KEY_FILE = CYCMClient.configManager.getModConfigDir().resolve("gemini_api_key.txt");

            // Правильне рішення:
            // Переконайтеся, що CYCMClient.configManager є public static
            // Це дозволить звертатися до нього без екземпляра
            // У CYCMClient.java має бути: public static ModConfigManager configManager;

            // Поточна помилка: 'cannot find symbol configManager'
            // Видаліть локальне оголошення Path API_KEY_FILE, зробіть його доступним напряму.

            // Правильний підхід для завантаження ключа, враховуючи, що configManager
            // ініціалізується в onInitializeClient() CYCMClient:
            if (CYCMClient.configManager == null) {
                Constants.LOGGER.warn("CYCM AI: configManager не ініціалізовано. Не вдалося завантажити ключ API.");
                CYCMClient.sendLocalizedMessage("ai_config_not_ready_warning");
                return;
            }

            // Тепер, коли configManager гарантовано ініціалізований, можна використовувати його
            Path finalApiKeyFile = CYCMClient.configManager.getModConfigDir().resolve("gemini_api_key.txt");
            CYCMClient.getInstance().ensureFile(finalApiKeyFile); // Ensure file exists

            try {
                apiKey = Files.readAllLines(finalApiKeyFile).stream()
                        .filter(l -> !l.trim().isEmpty() && !l.trim().startsWith("#"))
                        .findFirst().orElse(null);
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    CYCMClient.sendLocalizedMessage("ai_no_api_key_warning");
                } else {
                    Constants.LOGGER.info("CYCM AI: API Key loaded.");
                }
            } catch (IOException e) {
                CYCMClient.sendLocalizedMessage("ai_api_key_load_error", Text.literal(e.getMessage()));
                Constants.LOGGER.error("Error loading Gemini API key: {}", e.getMessage());
            }
        }

    public static void sendMessageToAI(String message, String currentCommandOutput) {
        if (!CYCMClient.configManager.getConfig().isModEnabled()) {
            CYCMClient.sendLocalizedMessage("ai_mod_disabled");
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            CYCMClient.sendLocalizedMessage("ai_no_api_key_warning");
            return;
        }

        stopCurrentAIGeneration(); // Stop any previous generation

        if (currentCommandOutput != null && !currentCommandOutput.trim().isEmpty()) {
            conversationHistory.add(new AIConversationEntry("user", "Output of last command: " + currentCommandOutput));
        }

        // Add user message to history
        conversationHistory.add(new AIConversationEntry("user", message));

        // Build the request body with conversation history
        String requestBody = buildRequestBody();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_API_BASE_URL + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        CYCMClient.sendLocalizedMessage("ai_generating_response");
        Constants.LOGGER.info("Sending request to Gemini API. Body: {}", requestBody);

        stopAiScheduler = Executors.newSingleThreadScheduledExecutor();
        stopAiScheduler.schedule(() -> {
            // If generation is still ongoing after a timeout, stop it
            if (stopAiScheduler != null && !stopAiScheduler.isShutdown()) {
                stopCurrentAIGeneration();
                CYCMClient.sendLocalizedMessage("ai_generation_timeout");
            }
        }, 30, TimeUnit.SECONDS); // Timeout after 30 seconds

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(AIClient::handleGeminiResponse)
                .exceptionally(e -> {
                    stopCurrentAIGeneration();
                    CYCMClient.sendLocalizedMessage("ai_request_error", Text.literal(e.getMessage()));
                    Constants.LOGGER.error("Gemini API request failed: {}", e.getMessage());
                    return null;
                });
    }

    private static String buildRequestBody() {
        StringBuilder content = new StringBuilder();
        content.append("{ \"contents\": [");
        // Add initial system instruction to the model
        content.append("{\"role\": \"user\", \"parts\": [{\"text\": \"You are a helpful Minecraft assistant. Respond concisely in Ukrainian. If a user asks you to perform an action, respond in JSON without Markdown, using the format: {\\\\\\\"message\\\\\\\": \\\\\\\"Your text response\\\\\\\", \\\\\\\"runCommand\\\\\\\": \\\\\\\"command to run, empty, or null\\\\\\\"}. If you need to run a command, always start it with a slash (/). You can use Minecraft commands, including complex ones with NBT or selectors. You also know about the repeater syntax for commands (+N Y where N is repeats, Y is delay in seconds) and command chaining with &&. For example, if I say \\\\\\\"summon a bunch of chickens\\\\\\\" you might respond {\\\\\\\"message\\\\\\\": \\\\\\\"Summoning chickens!\\\\\\\", \\\\\\\"runCommand\\\\\\\": \\\\\\\"/summon minecraft:chicken +10 1\\\\\\\"}. If the user gives you command output, use it to inform your next response. If you cannot fulfill a command, just explain it in the message field. Do not use Markdown in your JSON. Example: {\\\\\\\"message\\\\\\\": \\\\\\\"I've found a village!\\\\\\\", \\\\\\\"runCommand\\\\\\\": \\\\\\\"/locate structure minecraft:village_plains\\\\\\\"}.\"}]}, {\"role\": \"model\", \"parts\": [{\"text\": \"Зрозумів. Я готовий допомагати з Minecraft командами.\"}]}");

        // Add conversation history
        for (AIConversationEntry entry : conversationHistory) {
            content.append(", {\"role\": \"").append(entry.getRole()).append("\", \"parts\": [{\"text\": \"").append(escapeJson(entry.getText())).append("\"}]}");
        }
        content.append("]}");
        return content.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void handleGeminiResponse(String responseBody) {
        stopCurrentAIGeneration();
        Constants.LOGGER.info("Received Gemini response: {}", responseBody);
        try {
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("candidates") && jsonResponse.getAsJsonArray("candidates").size() > 0) {
                JsonObject candidate = jsonResponse.getAsJsonArray("candidates").get(0).getAsJsonObject();
                if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts")) {
                    com.google.gson.JsonArray parts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
                    if (parts.size() > 0 && parts.get(0).getAsJsonObject().has("text")) {
                        String rawText = parts.get(0).getAsJsonObject().get("text").getAsString();

                        AIResponse aiResponse = null;
                        try {
                            aiResponse = gson.fromJson(rawText, AIResponse.class);
                        } catch (JsonSyntaxException e) {
                            // If it's not clean JSON, assume it's just a message
                            CYCMClient.sendLocalizedMessage("ai_response", Text.literal(rawText));
                            conversationHistory.add(new AIConversationEntry("model", rawText));
                            return; // Don't try to run command
                        }

                        if (aiResponse != null) {
                            String message = aiResponse.getMessage();
                            String command = aiResponse.getRunCommand();

                            if (message != null && !message.isEmpty()) {
                                CYCMClient.sendLocalizedMessage("ai_response", Text.literal(message));
                            }

                            if (command != null && !command.trim().isEmpty()) {
                                if (command.startsWith("/")) {
                                    String baseCmd = COMMAND_PATTERN.matcher(command).find() ? COMMAND_PATTERN.matcher(command).group(1).toLowerCase() : "";
                                    if (!baseCmd.isEmpty() && CYCMClient.getInstance().isCmdBlocked(baseCmd)) {
                                        CYCMClient.sendLocalizedMessage("ai_cmd_blocked_warning", Text.literal(baseCmd));
                                    } else {
                                        CYCMClient.sendLocalizedMessage("ai_executing_command", Text.literal(command));
                                        // Передача команди до основного класу для виконання в грі
                                        MinecraftClient.getInstance().getNetworkHandler().sendChatCommand(command.substring(1)); // Remove the leading slash for sendChatCommand
                                        conversationHistory.add(new AIConversationEntry("model", "Executed command: " + command)); // Add to history
                                    }
                                } else {
                                    CYCMClient.sendLocalizedMessage("ai_invalid_command_format", Text.literal(command));
                                }
                            } else {
                                conversationHistory.add(new AIConversationEntry("model", message)); // Only add message if no command
                            }
                        } else {
                            CYCMClient.sendLocalizedMessage("ai_response", Text.literal(rawText));
                            conversationHistory.add(new AIConversationEntry("model", rawText));
                        }
                    }
                }
            } else if (jsonResponse.has("error")) {
                String errorMessage = jsonResponse.getAsJsonObject("error").get("message").getAsString();
                CYCMClient.sendLocalizedMessage("ai_api_error", Text.literal(errorMessage));
                Constants.LOGGER.error("Gemini API error: {}", errorMessage);
            } else {
                CYCMClient.sendLocalizedMessage("ai_malformed_response");
                Constants.LOGGER.warn("Malformed Gemini response: {}", responseBody);
            }
        } catch (JsonSyntaxException e) {
            CYCMClient.sendLocalizedMessage("ai_malformed_json", Text.literal(e.getMessage()));
            Constants.LOGGER.error("Malformed JSON from Gemini API: {}", e.getMessage());
            CYCMClient.sendLocalizedMessage("ai_response", Text.literal(responseBody)); // Show raw response if JSON parsing fails
        }
    }

    public static void stopCurrentAIGeneration() {
        if (stopAiScheduler != null) {
            stopAiScheduler.shutdownNow();
            stopAiScheduler = null;
            Constants.LOGGER.info("AI generation stopped.");
        }
    }

    public static String getLastExecutedCommandOutput() {
        return lastExecutedCommandOutput;
    }

    public static void setLastExecutedCommandOutput(String output) {
        lastExecutedCommandOutput = output;
        Constants.LOGGER.info("Set last command output: {}", output);
    }

    private static class AIConversationEntry {
        private String role; // "user" or "model"
        private String text;

        public AIConversationEntry(String role, String text) {
            this.role = role;
            this.text = text;
        }

        public String getRole() {
            return role;
        }

        public String getText() {
            return text;
        }
    }
}
