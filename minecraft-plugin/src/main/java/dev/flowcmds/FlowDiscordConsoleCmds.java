package dev.flowcmds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class FlowDiscordConsoleCmds extends JavaPlugin implements BridgeClient.Handler {

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I mix-ups

    private volatile Settings settings;
    private volatile String linkCode;
    private BridgeClient bridge;
    private ConsoleAppender appender;
    private CommandLog commandLog;
    private BukkitTask statusTask;

    /**
     * Hooked in onLoad instead of onEnable, so the console is captured from as early as possible.
     * What the server printed before that is read back from logs/latest.log.
     */
    @Override
    public void onLoad() {
        try {
            if (ConfigMigration.migrate(getDataFolder(), () -> getResource("config.yml"))) {
                getLogger().info("Converted the config of the old plugin version (backup: config-old.yml). The link code was kept.");
            }
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Could not convert the old config.yml: " + e);
        }
        saveDefaultConfig();
        loadSettings();
        loadOrCreateCode(false);

        bridge = new BridgeClient(() -> settings, () -> linkCode, Bukkit.getVersion(), getLogger(), this);
        bridge.setPlayerCounts(0, Bukkit.getMaxPlayers());
        appender = new ConsoleAppender(() -> settings, bridge);
        appender.start();
        rootLogger().addAppender(appender);

        // No worlds yet = real server start (not /reload): fetch the lines printed before us.
        if (Bukkit.getWorlds().isEmpty()) {
            try {
                bridge.backfill(LogBackfill.read(new File("logs", "latest.log"), settings,
                        appender::firstCaptured, 500));
            } catch (IOException | RuntimeException e) {
                getLogger().warning("Could not read the startup lines from logs/latest.log: " + e);
            }
        }
    }

    @Override
    public void onEnable() {
        commandLog = new CommandLog(new File(getDataFolder(), "commands.log").toPath(), getLogger());
        bridge.start();
        startStatusTask();
    }

    @Override
    public void onDisable() {
        if (statusTask != null) statusTask.cancel();
        if (appender != null) {
            rootLogger().removeAppender(appender);
            appender.stop();
        }
        if (bridge != null) bridge.shutdown();
        if (commandLog != null) commandLog.close();
    }

    private static Logger rootLogger() {
        return (Logger) LogManager.getRootLogger();
    }

    private void loadSettings() {
        reloadConfig();
        Settings s = new Settings(getConfig());
        for (String w : s.warnings) getLogger().warning(w);
        settings = s;
    }

    /**
     * The link code identifies this server at the bot: whoever links it in Discord controls
     * this console. Created once and kept in data.yml.
     */
    private void loadOrCreateCode(boolean forceNew) {
        File file = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        String c = data.getString("link-code", "");
        if (forceNew || !c.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) {
            SecureRandom rnd = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i == 4) b.append('-');
                b.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
            }
            c = b.toString();
            data.options().header("Link code of this server. Whoever links it in Discord controls this console - keep it private.\n"
                    + "Run /flowcmds reset confirm to get a new one (the old Discord link stops working).");
            data.set("link-code", c);
            data.set("server-token", null); // from plugin 1.1.0, no longer used
            try {
                getDataFolder().mkdirs();
                data.save(file);
            } catch (IOException e) {
                getLogger().severe("Could not save data.yml - the link code will change on every restart: " + e);
            }
        }
        linkCode = c;
    }

    private void startStatusTask() {
        if (statusTask != null) statusTask.cancel();
        long ticks = settings.statusIntervalSeconds * 20L;
        statusTask = Bukkit.getScheduler().runTaskTimer(this, () -> bridge.sendStats(collectStats()), 100L, ticks);
    }

    // ------------------------------------------------------------------ BridgeClient.Handler (bridge thread)

    @Override
    public void onCommand(String command) {
        String cmd = command.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
        if (cmd.isEmpty()) return;

        if (CONTROL.matcher(cmd).find()) {
            reject(cmd.replaceAll("\\p{Cntrl}", " "), "FAILED", "contains line breaks or control characters");
            return;
        }
        if (!settings.allowCommands) {
            reject(cmd, "DISABLED", "commands from Discord are disabled (allow-commands: false)");
            return;
        }
        if (!isEnabled()) {
            reject(cmd, "FAILED", "the server is still starting - try again in a moment");
            return;
        }
        String finalCmd = cmd;
        try {
            // Commands must run on the main server thread - exactly like typing into the console.
            Bukkit.getScheduler().runTask(this, () -> runFromDiscord(finalCmd));
        } catch (RuntimeException e) { // IllegalPluginAccessException while disabling
            reject(cmd, "FAILED", "the server is shutting down");
        }
    }

    /** Main thread. */
    private void runFromDiscord(String cmd) {
        Settings s = settings;
        String blocked = s.blockedBy(cmd, this::namesOf);
        if (blocked != null) {
            reject(cmd, "BLOCKED", "'" + blocked + "' is in blocked-commands");
            return;
        }

        // Same wording as Paper uses for players, so it reads like a normal console
        if (s.logCommandUsage) getLogger().info("Discord issued server command: /" + cmd);
        boolean ok;
        String error = null;
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            ok = false;
            error = t.toString();
            getLogger().log(java.util.logging.Level.WARNING, "Command from Discord failed: /" + cmd, t);
        }
        audit(cmd, ok ? "OK" : "FAILED", ok ? null : error != null ? error : "unknown command");
    }

    /** The bot gets no answer for commands, so the reason goes into the console (= the Discord channel). */
    private void reject(String cmd, String result, String reason) {
        getLogger().warning("Command from Discord not run: /" + cmd + " (" + reason + ")");
        audit(cmd, result, reason);
    }

    private void audit(String cmd, String result, String detail) {
        if (settings.commandLogFile && commandLog != null) commandLog.write("Discord", cmd, result, detail);
    }

    /** Real name + aliases of a plugin command ("lp" -> luckperms, lp, perm, ...). Main thread. */
    private Collection<String> namesOf(String name) {
        PluginCommand pc;
        try {
            pc = getServer().getPluginCommand(name);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (pc == null) return List.of();
        List<String> names = new ArrayList<>(pc.getAliases());
        names.add(pc.getName());
        names.add(pc.getLabel());
        return names;
    }

    @Override
    public void onStatsRequest() {
        try {
            Bukkit.getScheduler().runTask(this, () -> bridge.sendStats(collectStats()));
        } catch (RuntimeException ignored) { // not enabled yet / disabling - the status timer sends it later
        }
    }

    /** Main thread only. Field names and formats are what the bot's /status shows. */
    private JsonObject collectStats() {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        bridge.setPlayerCounts(online, max);

        JsonObject o = new JsonObject();
        o.addProperty("players", online);
        o.addProperty("maxPlayers", max);
        JsonArray names = new JsonArray();
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (n++ >= 100) break;
            names.add(p.getName());
        }
        o.add("playerNames", names);
        double[] tps = paperTps();
        o.addProperty("tps", tps == null || tps.length < 3 ? "N/A"
                : String.format(Locale.ROOT, "%.1f / %.1f / %.1f", Math.min(20, tps[0]), Math.min(20, tps[1]), Math.min(20, tps[2])));
        Runtime rt = Runtime.getRuntime();
        o.addProperty("ram", (rt.totalMemory() - rt.freeMemory()) / 1048576L + "/" + rt.maxMemory() / 1048576L + " MB");
        o.addProperty("uptime", formatUptime(ManagementFactory.getRuntimeMXBean().getUptime() / 1000L));
        int chunks = 0, entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getLoadedChunks().length;
            entities += w.getEntities().size();
        }
        o.addProperty("chunks", chunks);
        o.addProperty("entities", entities);
        o.addProperty("plugins", Bukkit.getPluginManager().getPlugins().length);
        return o;
    }

    private static String formatUptime(long s) {
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60;
        return (d > 0 ? d + "d " : "") + (d > 0 || h > 0 ? h + "h " : "") + m + "m";
    }

    /** Paper/Purpur have Server#getTPS(), plain Spigot does not. */
    private static double[] paperTps() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            return r instanceof double[] d ? d : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ /flowcmds

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "FlowDiscordConsoleCmds " + getDescription().getVersion());
                sender.sendMessage(ChatColor.GRAY + "Bot: " + ChatColor.WHITE + settings.botUrl);
                sender.sendMessage(ChatColor.GRAY + "State: " + ChatColor.WHITE + bridge.state());
                sender.sendMessage(ChatColor.GRAY + "Link code: " + ChatColor.AQUA + linkCode
                        + (bridge.isConnected() ? (bridge.isLinked() ? ChatColor.GREEN + "  (linked)" : ChatColor.YELLOW + "  (not linked yet)") : ""));
                sender.sendMessage(ChatColor.GRAY + "Lines sent: " + ChatColor.WHITE + bridge.sentLines()
                        + ChatColor.GRAY + "   waiting: " + ChatColor.WHITE + bridge.queuedLines());
                String err = bridge.lastError();
                sender.sendMessage(ChatColor.GRAY + "Last error: " + (err == null ? ChatColor.GREEN + "none" : ChatColor.RED + err));
            }
            case "code" -> {
                sender.sendMessage(ChatColor.GRAY + "Link code: " + ChatColor.AQUA + ChatColor.BOLD + linkCode);
                if (bridge.isLinked()) {
                    sender.sendMessage(ChatColor.GREEN + "Already linked. " + ChatColor.GRAY
                            + "It can be linked in more channels with the same command.");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Run " + ChatColor.WHITE + "/link " + linkCode
                            + ChatColor.GRAY + " in the Discord channel that should become the console.");
                }
                if (!bridge.isConnected()) sender.sendMessage(ChatColor.RED + "Not connected to the bot right now: " + bridge.state());
            }
            case "reconnect" -> {
                bridge.reconnect();
                sender.sendMessage(ChatColor.GREEN + "Reconnecting to the bot...");
            }
            case "reload" -> {
                Settings old = settings;
                loadSettings();
                startStatusTask();
                if (!old.botUrl.equals(settings.botUrl) || !old.serverName.equals(settings.serverName)) bridge.reconnect();
                sender.sendMessage(ChatColor.GREEN + "Config reloaded." + (settings.botUri == null ? ChatColor.RED + " (bot-url is invalid!)" : ""));
            }
            case "reset" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(ChatColor.YELLOW + "This creates a new link code. Discord channels linked to the old code stop working.");
                    sender.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.WHITE + "/flowcmds reset confirm" + ChatColor.YELLOW + " to continue.");
                    return true;
                }
                loadOrCreateCode(true);
                bridge.reconnect();
                sender.sendMessage(ChatColor.GREEN + "New link code: " + ChatColor.AQUA + linkCode
                        + ChatColor.GREEN + " - run /link " + linkCode + " in Discord.");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/" + label + " <status|code|reconnect|reload|reset>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "code", "reconnect", "reload", "reset").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
