package org.craftllc.minecraft.mod.cycm;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.craftllc.minecraft.mod.cycm.config.ModConfigManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import org.craftllc.minecraft.mod.cycm.ai.AIClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CYCMClient implements ClientModInitializer {
    private static final Path MOD_CFG_DIR = FabricLoader.getInstance().getConfigDir().resolve(Constants.MOD_ID);
    private static final Path CMD_FILE = MOD_CFG_DIR.resolve("commands_list.txt");
    private static final Path CHAT_FILE = MOD_CFG_DIR.resolve("chat.txt");
    private static final Path BLOCKED_FILE = MOD_CFG_DIR.resolve("blocked_commands.txt");
    private static final Path REPEATING_FILE = MOD_CFG_DIR.resolve("repeating_settings.txt");
    private static final Path CMD_LOG_FILE = MOD_CFG_DIR.resolve("commands_log.txt");
    private static final Path CHAT_LOG_FILE = MOD_CFG_DIR.resolve("chat_log.txt");

    private static ScheduledExecutorService scheduler;
    public static ModConfigManager configManager;
    private static Set<String> blockedCommands = Collections.synchronizedSet(new HashSet<>());
    private static int maxRepeats = 20;
    private static int maxDelaySeconds = 5;
    private static CYCMClient instance;

    public CYCMClient() {
        instance = this;
    }

    public static CYCMClient getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        Constants.LOGGER.info("CYCM: Ініціалізація.");
        configManager = ModConfigManager.getInstance();
        configManager.loadConfig();
        configManager.startWatchingConfigFile();
        Constants.LOGGER.info("CYCM Мод " + (configManager.getConfig().isModEnabled() ? "увімкнено" : "вимкнено"));
        loadBlockedCommands();
        loadRepeatingSettings();
        AIClient.loadApiKey(); // Завантажуємо ключ API для AI
        registerEventHandlers();
        registerClientCommands();
    }

    private void registerEventHandlers() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                if (configManager.getConfig().isModEnabled() && (scheduler == null || scheduler.isShutdown())) {
                    Constants.LOGGER.info("Гравець увійшов. Запускаю обробку файлів.");
                    startFileProcessing();
                }
            } else {
                stopFileProcessing();
            }
        });

        // Цей блок має бути в CYCMClient, бо це ModInitializer
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Constants.LOGGER.info("Вимкнення. Зупиняю обробку файлів.");
            configManager.stopWatchingConfigFile();
            stopFileProcessing();
            AIClient.stopCurrentAIGeneration(); // Зупиняємо генерацію AI при вимкненні
        }));

        // Перехоплення вхідних повідомлень для передачі ШІ
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            // Listen for incoming game messages by registering a packet listener
            // We'll use ClientTickEvents instead to capture chat messages
        });
        
        // Add a tick event to monitor chat for AI system
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null && client.player != null && client.inGameHud != null) {
                // Monitor chat messages for AI system
                // This is a simplified approach - in a real implementation, you might want to
                // use mixins or packet listeners to capture messages more reliably
            }
        });
    }

    private void startFileProcessing() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::processFiles, 0, 2, TimeUnit.SECONDS);
            Constants.LOGGER.info("Обробку файлів запущено.");
        }
    }

    private void stopFileProcessing() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            Constants.LOGGER.info("Обробку файлів зупинено.");
        }
    }

    public static void setModEnabled(boolean enabled) {
        if (configManager.getConfig().isModEnabled() == enabled) {
            sendLocalizedMessage("mod_already_state", (enabled ? Text.translatable("cycm.state.enabled") : Text.translatable("cycm.state.disabled")));
            return;
        }
        configManager.getConfig().setModEnabled(enabled);
        configManager.saveConfig();
        sendLocalizedMessage("mod_state", (enabled ? Text.translatable("cycm.state.enabled") : Text.translatable("cycm.state.disabled")));
        if (enabled) getInstance().startFileProcessing();
        else getInstance().stopFileProcessing();
    }

    private void processFiles() {
        if (!configManager.getConfig().isModEnabled() || MinecraftClient.getInstance().player == null) {
            stopFileProcessing();
            return;
        }
        procClearFirst(CMD_FILE, this::procCmdLine, CMD_LOG_FILE, "CMD");
        procClearFirst(CHAT_FILE, this::procChatLine, CHAT_LOG_FILE, "CHAT");
    }

    private void procClearFirst(Path fp, LineProcessor proc, Path lfp, String type) {
        ensureFile(fp);
        ensureFile(lfp);
        try {
            List<String> lines = Files.readAllLines(fp);
            if (lines.isEmpty()) return;
            String firstLine = lines.get(0);
            try (BufferedWriter w = Files.newBufferedWriter(lfp, StandardOpenOption.APPEND)) {
                w.write(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + firstLine);
                w.newLine();
            } catch (IOException e) {
                Constants.LOGGER.error("Помилка запису логу {}: {}", lfp.getFileName(), e.getMessage());
            }
            proc.process(firstLine);
            if (lines.size() > 1) {
                Files.write(fp, lines.subList(1, lines.size()));
            } else {
                Files.write(fp, new byte[0]);
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка обробки файлу {}: {}", fp.getFileName(), e.getMessage());
        }
    }

    private void procCmdLine(String line) {
        int idx = line.lastIndexOf(':');
        // Перевіряємо, чи є двокрапка і чи вона не в кінці рядка
        if (idx == -1 || idx == line.length() - 1) {
            sendLocalizedMessage("bad_cmd_format", Text.literal(line));
            return;
        }
        String nick = line.substring(0, idx).trim();
        String fullCmd = line.substring(idx + 1).trim();

        Constants.LOGGER.info("CYCM Debug: Raw fullCmd from file: '{}'", fullCmd);

        // Ця перевірка тепер дійсно перевіряє команду після ніка
        if (!fullCmd.startsWith("/")) {
            sendLocalizedMessage("cmd_must_start_with_slash", Text.literal(line)); // Показуємо весь рядок для контексту
            return;
        }

        for (String cmd : fullCmd.substring(1).split("&&")) {
            execSingleCmd(nick, cmd.trim());
        }
    }

    private void execSingleCmd(String nick, String cmd) {
        int reps = 1;
        int delay = 0;
        Matcher m = Pattern.compile("^(.*?)\\s*\\+(\\d+)(?:\\s+(\\d+))?$").matcher(cmd);
        if (m.matches()) {
            cmd = m.group(1).trim();
            try {
                reps = Integer.parseInt(m.group(2));
                if (reps > maxRepeats) {
                    sendLocalizedMessage("repeats_exceed_max", Text.literal(String.valueOf(reps)), Text.literal(String.valueOf(maxRepeats)));
                    return;
                }
            } catch (NumberFormatException e) {
                sendLocalizedMessage("bad_repeats", Text.literal(cmd));
                return;
            }
            if (m.group(3) != null) {
                try {
                    delay = Integer.parseInt(m.group(3));
                    if (delay > maxDelaySeconds) {
                        sendLocalizedMessage("delay_exceed_max", Text.literal(String.valueOf(delay)), Text.literal(String.valueOf(maxDelaySeconds)));
                        return;
                    }
                } catch (NumberFormatException e) {
                    sendLocalizedMessage("bad_delay", Text.literal(cmd));
                    return;
                }
            }
        }

        String baseCmd = cmd.split(" ")[0].toLowerCase();
        if (isCmdBlocked(baseCmd)) {
            sendLocalizedMessage("cmd_blocked", Text.literal("/" + baseCmd));
            return;
        }

        final String fCmd = cmd;
        final int fReps = reps;
        final int fDel = delay;
        for (int r = 0; r < fReps; r++) {
            final int currRep = r + 1;
            scheduler.schedule(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.literal("§f" + nick + " ").append(Text.translatable("cycm.message.cmd_executed", Text.literal("/" + fCmd)))
                                    .append(fReps > 1 ? Text.translatable("cycm.message.repetition_info", Text.literal(String.valueOf(currRep)), Text.literal(String.valueOf(fReps))).formatted(Formatting.GRAY) : Text.empty()), false);
                    MinecraftClient.getInstance().getNetworkHandler().sendChatCommand(fCmd);
                }
            }, (long) r * fDel, TimeUnit.SECONDS);
        }
    }

    private void execCmdInGame(String cmd) {
        if (MinecraftClient.getInstance().player == null) {
            sendLocalizedMessage("no_player");
            return;
        }
        Constants.LOGGER.info("execCmdInGame: Отримано команду: '{}'", cmd);
        int reps = 1;
        int delay = 0;
        Matcher m = Pattern.compile("^(.*?)\\s*\\+(\\d+)(?:\\s+(\\d+))?$").matcher(cmd);
        if (m.matches()) {
            cmd = m.group(1).trim();
            try {
                reps = Integer.parseInt(m.group(2));
                if (reps > maxRepeats) {
                    sendLocalizedMessage("repeats_exceed_max", Text.literal(String.valueOf(reps)), Text.literal(String.valueOf(maxRepeats)));
                    return;
                }
            } catch (NumberFormatException e) {
                sendLocalizedMessage("bad_repeats", Text.literal(cmd));
                return;
            }
            if (m.group(3) != null) {
                try {
                    delay = Integer.parseInt(m.group(3));
                    if (delay > maxDelaySeconds) {
                        sendLocalizedMessage("delay_exceed_max", Text.literal(String.valueOf(delay)), Text.literal(String.valueOf(maxDelaySeconds)));
                        return;
                    }
                } catch (NumberFormatException e) {
                    sendLocalizedMessage("bad_delay", Text.literal(cmd));
                    return;
                }
            }
        }
        String baseCmd = cmd.split(" ")[0].toLowerCase();
        if (isCmdBlocked(baseCmd) && !isModCmd(baseCmd)) {
            sendLocalizedMessage("cmd_blocked", Text.literal("/" + baseCmd));
            return;
        }
        final String fCmd = cmd;
        final int fReps = reps;
        final int fDel = delay;
        for (int r = 0; r < fReps; r++) {
            final int currRep = r + 1;
            scheduler.schedule(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.translatable("cycm.message.executing_command", Text.literal("/" + fCmd))
                                    .append(fReps > 1 ? Text.translatable("cycm.message.repetition_info", Text.literal(String.valueOf(currRep)), Text.literal(String.valueOf(fReps))).formatted(Formatting.GRAY) : Text.empty()), false);
                    MinecraftClient.getInstance().getNetworkHandler().sendChatCommand(fCmd);
                }
            }, (long) r * fDel, TimeUnit.SECONDS);
        }
    }

    // Оновлена логіка: тільки "cycm" не може бути заблокований
    private boolean isModCmd(String cmd) {
        return cmd.equals("cycm") || cmd.equals("ai") || cmd.equals("stopai"); // Додано AI команди
    }

    private void procChatLine(String line) {
        int idx = line.lastIndexOf(':');
        if (idx == -1 || idx == line.length() - 1) {
            sendLocalizedMessage("bad_chat_format", Text.literal(line));
            return;
        }
        String nick = line.substring(0, idx).trim();
        String msg = line.substring(idx + 1).trim();
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("<").append(Text.literal(nick).formatted(Formatting.WHITE)).append("> ").append(Text.literal(msg)), false);
        }
    }

    public void ensureFile(Path fp) { // Зроблено public для AIClient
        try {
            if (!Files.exists(fp)) {
                Files.createDirectories(fp.getParent());
                Files.createFile(fp);
                Constants.LOGGER.info("Створено {}.", fp.getFileName());
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка створення файлу {}: {}", fp.getFileName(), e.getMessage());
        }
    }

    private void loadBlockedCommands() {
        ensureFile(BLOCKED_FILE);
        try {
            synchronized (blockedCommands) {
                blockedCommands.clear();
                Files.readAllLines(BLOCKED_FILE).stream()
                        .filter(l -> !l.trim().isEmpty() && !l.trim().startsWith("#"))
                        .map(String::trim)
                        .map(l -> l.startsWith("/") ? l.substring(1).toLowerCase() : l.toLowerCase())
                        .forEach(blockedCommands::add);
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка читання файлу заблокованих команд: {}", e.getMessage());
        }
        initBlockedCommands();
    }

    private void saveBlockedCommands() {
        try {
            Files.write(BLOCKED_FILE, blockedCommands.stream().map(s -> "/" + s).collect(Collectors.toList()));
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка збереження файлу заблокованих команд: {}", e.getMessage());
        }
    }

    public boolean isCmdBlocked(String cmd) { // Зроблено public для AIClient
        return blockedCommands.contains(cmd.split(" ")[0].toLowerCase());
    }

    // Оновлений список стандартних заблокованих команд
    private void initBlockedCommands() {
        boolean chg = false;
        // Завжди заблоковані за замовчуванням
        Set<String> defaultBlocked = new HashSet<>(Arrays.asList(
                "op", "clear", "deop", "kill", "execute", "ban", "reload", "kick", "stop", "particle"
        ));

        // Додаємо лише ті, яких немає в поточному blockedCommands
        for (String cmd : defaultBlocked) {
            if (blockedCommands.add(cmd.toLowerCase())) {
                chg = true;
            }
        }
        // Переконатися, що 'cycm', 'ai', 'stopai' завжди в blockedCommands, але їх не можна unblock
        if (blockedCommands.add("cycm")) {
            chg = true;
        }
        if (blockedCommands.add("ai")) {
            chg = true;
        }
        if (blockedCommands.add("stopai")) {
            chg = true;
        }

        if (chg) saveBlockedCommands();
    }

    private void blockCommand(String cmd) {
        String cleanCmd = cmd.toLowerCase().startsWith("/") ? cmd.substring(1).toLowerCase() : cmd.toLowerCase();
        if (isModCmd(cleanCmd)) {
            sendLocalizedMessage("mod_cmd_cannot_be_blocked", Text.literal("/" + cleanCmd));
            return;
        }
        if (blockedCommands.add(cleanCmd)) {
            sendLocalizedMessage("cmd_blocked_success", Text.literal("/" + cleanCmd));
            saveBlockedCommands();
        } else {
            sendLocalizedMessage("cmd_already_blocked", Text.literal("/" + cleanCmd));
        }
    }

    private void unblockCommand(String cmd) {
        String cleanCmd = cmd.toLowerCase().startsWith("/") ? cmd.substring(1).toLowerCase() : cmd.toLowerCase();
        if (isModCmd(cleanCmd)) {
            sendLocalizedMessage("mod_cmd_cannot_be_unblocked", Text.literal("/" + cleanCmd));
            return;
        }
        if ("all".equalsIgnoreCase(cleanCmd)) {
            // Розблокувати все, крім тих, що в initBlockedCommands (тобто, відновити дефолтний список)
            synchronized (blockedCommands) {
                blockedCommands.clear();
            }
            initBlockedCommands(); // Відновлює стандартний список заблокованих
            sendLocalizedMessage("all_cmds_unblocked");
        } else if (blockedCommands.remove(cleanCmd)) {
            sendLocalizedMessage("cmd_unblocked_success", Text.literal("/" + cleanCmd));
            saveBlockedCommands();
        } else {
            sendLocalizedMessage("cmd_not_blocked", Text.literal("/" + cleanCmd));
        }
    }

    private void loadRepeatingSettings() {
        ensureFile(REPEATING_FILE);
        try {
            String sLine = Files.readAllLines(REPEATING_FILE).stream().filter(l -> !l.trim().isEmpty() && !l.trim().startsWith("#")).findFirst().orElse(null);
            if (sLine != null && sLine.split(":").length == 2) {
                try {
                    maxRepeats = Integer.parseInt(sLine.split(":")[0].trim());
                    maxDelaySeconds = Integer.parseInt(sLine.split(":")[1].trim());
                } catch (NumberFormatException e) {
                    Constants.LOGGER.error("Невірні налаштування повторів: {}. Використовуються стандартні.", sLine);
                }
            } else {
                Files.write(REPEATING_FILE, Collections.singletonList(maxRepeats + ":" + maxDelaySeconds));
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка читання налаштувань повторів: {}", e.getMessage());
        }
    }

    // Нові методи для встановлення maxRepeats та maxDelaySeconds
    private void setMaxRepeats(int num) {
        if (num <= 0) {
            sendLocalizedMessage("num_repeats_positive_warning");
            return;
        }
        maxRepeats = num;
        saveRepeatingSettings();
        sendLocalizedMessage("repeats_set_success", Text.literal(String.valueOf(num)));
    }

    private void setMaxDelaySeconds(int delay) {
        if (delay < 0) {
            sendLocalizedMessage("delay_positive_warning");
            return;
        }
        maxDelaySeconds = delay;
        saveRepeatingSettings();
        sendLocalizedMessage("delay_set_success", Text.literal(String.valueOf(delay)));
    }

    private void saveRepeatingSettings() {
        try {
            Files.write(REPEATING_FILE, Collections.singletonList(maxRepeats + ":" + maxDelaySeconds));
        } catch (IOException e) {
            Constants.LOGGER.error("Помилка збереження налаштувань повторів: {}", e.getMessage());
        }
    }


    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((disp, regAcc) -> {
            disp.register(literal("tnt").executes(ctx -> {
                sendToSrv("summon minecraft:tnt");
                return 1;
            }));
            disp.register(literal("blocklist").executes(ctx -> {
                dispBlockList();
                return 1;
            }));
            disp.register(literal("ka").executes(ctx -> execKillAura(20.0)));
            disp.register(literal("killaura").executes(ctx -> execKillAura(20.0)));
            disp.register(literal("ke").executes(ctx -> execKillEntities()));
            disp.register(literal("killentities").executes(ctx -> execKillEntities()));
            disp.register(literal("cycm")
                    .executes(ctx -> {
                        sendLocalizedMessage("cycm_usage");
                        return 1;
                    })
                    .then(literal("block").then(argument("cmd", StringArgumentType.greedyString()).suggests((ctx, builder) -> sugBlock(ctx, builder)).executes(ctx -> {
                        blockCommand(StringArgumentType.getString(ctx, "cmd"));
                        return 1;
                    })))
                    .then(literal("unblock").then(argument("cmd", StringArgumentType.greedyString()).suggests(this::sugUnblock).executes(ctx -> {
                        unblockCommand(StringArgumentType.getString(ctx, "cmd"));
                        return 1;
                    })))
                    .then(literal("on").executes(ctx -> {
                        setModEnabled(true);
                        return 1;
                    }))
                    .then(literal("off").executes(ctx -> {
                        setModEnabled(false);
                        return 1;
                    }))
                    .then(literal("restart").executes(ctx -> {
                        restartMod();
                        return 1;
                    }))
                    .then(literal("resetfile").then(argument("fname", StringArgumentType.string()).suggests(this::sugReset).executes(ctx -> {
                        resetConfig(StringArgumentType.getString(ctx, "fname"));
                        return 1;
                    })))
                    .then(literal("execute").then(argument("cmd_reps", StringArgumentType.greedyString()).executes(ctx -> {
                        execCmdInGame(StringArgumentType.getString(ctx, "cmd_reps"));
                        return 1;
                    })))
                    // Нові підкоманди для повторів та затримки
                    .then(literal("num")
                            .then(argument("N", IntegerArgumentType.integer()).executes(ctx -> {
                                setMaxRepeats(IntegerArgumentType.getInteger(ctx, "N"));
                                return 1;
                            })))
                    .then(literal("delay")
                            .then(argument("Y", IntegerArgumentType.integer()).executes(ctx -> {
                                setMaxDelaySeconds(IntegerArgumentType.getInteger(ctx, "Y"));
                                return 1;
                            })))
            );
            disp.register(literal("ce")
                    .then(argument("cmd_reps", StringArgumentType.greedyString()).executes(ctx -> {
                        execCmdInGame(StringArgumentType.getString(ctx, "cmd_reps"));
                        return 1;
                    }))
            );
            // Нові команди для AI
            disp.register(literal("ai")
                    .then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> {
                        String prompt = StringArgumentType.getString(ctx, "prompt");
                        AIClient.sendMessageToAI(prompt, AIClient.getLastExecutedCommandOutput());
                        AIClient.setLastExecutedCommandOutput(null); // Clear after use
                        return 1;
                    }))
            );
            disp.register(literal("stopai").executes(ctx -> {
                AIClient.stopCurrentAIGeneration();
                sendLocalizedMessage("ai_stopped_generation");
                return 1;
            }));
        });
    }

    private CompletableFuture<Suggestions> sugBlock(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
        // Залишаємо порожнім, бо список команд для блокування може бути будь-яким
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> sugUnblock(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase();
        // Пропонуємо тільки ті команди, які заблоковані і не є "cycm", "ai", "stopai"
        blockedCommands.stream().filter(c -> !c.equals("cycm") && !c.equals("ai") && !c.equals("stopai") && c.startsWith(rem)).forEach(b::suggest);
        if ("all".startsWith(rem)) b.suggest("all");
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> sugReset(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase();
        if ("blocked_commands.txt".startsWith(rem)) b.suggest("blocked_commands.txt");
        if ("repeating_settings.txt".startsWith(rem)) b.suggest("repeating_settings.txt");
        return b.buildFuture();
    }

    private void resetConfig(String fname) {
        Path tf;
        Runnable pa = null;
        if (fname.equalsIgnoreCase("blocked_commands.txt")) {
            tf = BLOCKED_FILE;
            pa = this::loadBlockedCommands;
        } else if (fname.equalsIgnoreCase("repeating_settings.txt")) {
            tf = REPEATING_FILE;
            pa = this::loadRepeatingSettings;
        } else {
            sendLocalizedMessage("unknown_file", Text.literal(fname));
            return;
        }
        try {
            if (Files.exists(tf)) Files.delete(tf);
            ensureFile(tf);
            if (pa != null) pa.run();
            sendLocalizedMessage("file_reset_success", Text.literal(fname));
        } catch (IOException e) {
            sendLocalizedMessage("reset_error", Text.literal(fname), Text.literal(e.getMessage()));
        }
    }

    private void restartMod() {
        sendLocalizedMessage("restarting_cycm_message");
        Constants.LOGGER.info("Перезапускаю мод...");
        stopFileProcessing();
        configManager.stopWatchingConfigFile();
        blockedCommands.clear();
        AIClient.stopCurrentAIGeneration(); // Зупиняємо генерацію AI при рестарті
        configManager.loadConfig();
        configManager.startWatchingConfigFile();
        loadBlockedCommands();
        loadRepeatingSettings();
        AIClient.loadApiKey(); // Перезавантажуємо ключ AI API
        if (configManager.getConfig().isModEnabled() && MinecraftClient.getInstance().player != null) {
            startFileProcessing();
            sendLocalizedMessage("cycm_restarted_success");
        } else {
            sendLocalizedMessage("mod_off_no_player_auto_start");
        }
    }

    private void dispBlockList() {
        if (blockedCommands.isEmpty()) {
            sendLocalizedMessage("no_blocked_cmds");
        } else {
            sendLocalizedMessage("blocked_cmds_header");
            synchronized (blockedCommands) {
                blockedCommands.stream().sorted().forEach(cmd -> sendLocalizedMessage("blocked_cmd_item", Text.literal("/" + cmd)));
            }
        }
    }

    private int execKillAura(double r) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) {
            sendLocalizedMessage("no_player");
            return 0;
        }
        sendToSrv(String.format("kill @e[type=!player,distance=..%.0f]", r));
        sendLocalizedMessage("executing_killaura", Text.literal(String.valueOf((int) r)));
        if (c.world != null) {
            Vec3d pPos = c.player.getPos();
            int numP = 30;
            for (int i = 0; i < numP; i++) {
                double angle = 2 * Math.PI * i / numP;
                double x = pPos.x + r * Math.cos(angle);
                double z = pPos.z + r * Math.sin(angle);
                c.world.addParticle(ParticleTypes.CRIT, x, pPos.y + c.player.getHeight() / 2, z, 0, 0.1, 0);
            }
        }
        return 1;
    }

    private int execKillEntities() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) {
            sendLocalizedMessage("no_player");
            return 0;
        }
        sendToSrv("kill @e[type=!player]");
        sendLocalizedMessage("executing_killentities");
        return 1;
    }

    @FunctionalInterface
    private interface LineProcessor {
        void process(String line);
    }

    public static void sendLocalizedMessage(String key, Object... args) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            Object[] processedArgs = Arrays.stream(args).map(arg -> arg instanceof String ? Text.literal((String) arg) : arg).toArray();
            Text message = Text.translatable("cycm.message." + key, processedArgs);
            Formatting defaultColor = Formatting.GOLD;

            if (key.startsWith("bad_") || key.endsWith("_error") || key.endsWith("_warning") || key.equals("cmd_blocked") || key.equals("mod_cmd_cannot_be_blocked") || key.equals("mod_cmd_cannot_be_unblocked") || key.equals("no_player") || key.startsWith("ai_")) {
                message = message.copy().formatted(Formatting.RED);
            } else if (key.endsWith("_success") || key.equals("cmd_unblocked_success") || key.equals("all_cmds_unblocked")) {
                message = message.copy().formatted(Formatting.GREEN);
            } else if (key.endsWith("_message") || key.equals("restarting_cycm_message") || key.equals("mod_off_no_player_auto_start") || key.equals("no_blocked_cmds") || key.equals("blocked_cmds_header")) {
                message = message.copy().formatted(Formatting.AQUA);
            } else if (key.endsWith("already_blocked") || key.endsWith("already_state") || key.equals("cmd_not_blocked")) {
                message = message.copy().formatted(Formatting.YELLOW);
            } else {
                message = message.copy().formatted(defaultColor);
            }
            client.player.sendMessage(message, false);
        }
    }

    public static void sendMsg(Text msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) c.player.sendMessage(msg, false);
    }

    public static void sendMsg(String msg) {
        sendMsg(Text.literal(msg));
    }

    private void sendToSrv(String cmd) {
        if (MinecraftClient.getInstance().player != null) MinecraftClient.getInstance().getNetworkHandler().sendChatCommand(cmd);
    }
}
