package de.flowdev.consolecmds;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

public class StatsTask {

    private final FlowDiscordConsoleCmds plugin;
    private BukkitTask task;

    public StatsTask(FlowDiscordConsoleCmds plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Run on main thread so Bukkit API calls are safe; every 600 ticks = 30 seconds
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::collect, 600L, 600L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void collect() {
        BotWebSocketClient ws = plugin.getWsClient();
        if (ws == null || !ws.isOpen()) return;

        JsonObject stats = new JsonObject();

        // Players
        JsonArray names = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        stats.add("playerNames", names);
        stats.addProperty("players",    Bukkit.getOnlinePlayers().size());
        stats.addProperty("maxPlayers", Bukkit.getMaxPlayers());

        // TPS (Paper/Spigot expose getTPS via reflection)
        stats.addProperty("tps", getTps());

        // RAM
        Runtime rt   = Runtime.getRuntime();
        long    used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long    max  = rt.maxMemory() / 1024 / 1024;
        stats.addProperty("ram", used + "/" + max + " MB");

        // Uptime
        long elapsed = System.currentTimeMillis() - plugin.getStartTime();
        stats.addProperty("uptime", formatUptime(elapsed));

        // Worlds: chunks + entities
        int chunks   = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks   += w.getLoadedChunks().length;
            entities += w.getEntities().size();
        }
        stats.addProperty("chunks",   chunks);
        stats.addProperty("entities", entities);

        // Plugins
        stats.addProperty("plugins", Bukkit.getPluginManager().getPlugins().length);

        ws.sendStats(stats);
    }

    private String getTps() {
        try {
            double[] tps = (double[]) Bukkit.getServer().getClass()
                    .getMethod("getTPS").invoke(Bukkit.getServer());
            return String.format("%.1f / %.1f / %.1f", tps[0], tps[1], tps[2]);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private String formatUptime(long ms) {
        long hours   = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
