package de.flowdev.consolecmds;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.util.UUID;
import java.util.logging.Logger;

public class FlowDiscordConsoleCmds extends JavaPlugin {

    private BotWebSocketClient wsClient;
    private ConsoleLogHandler logHandler;
    private StatsTask statsTask;
    private String linkCode;
    private long startTime;

    @Override
    public void onEnable() {
        startTime = System.currentTimeMillis();
        saveDefaultConfig();
        resolveCode();

        getLogger().info("================================================");
        getLogger().info("  FlowDiscordConsoleCmds");
        getLogger().info("  Link code: " + linkCode);
        getLogger().info("  Run /link " + linkCode + " in Discord");
        getLogger().info("================================================");

        logHandler = new ConsoleLogHandler(this);
        Logger.getLogger("").addHandler(logHandler);

        getCommand("flowcmds").setExecutor(new FlowCmdsCommand(this));

        connectAsync();

        statsTask = new StatsTask(this);
        statsTask.start();
    }

    @Override
    public void onDisable() {
        if (statsTask != null) statsTask.stop();
        if (logHandler != null) Logger.getLogger("").removeHandler(logHandler);
        if (wsClient != null) {
            try { wsClient.closeBlocking(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void resolveCode() {
        String configured = getConfig().getString("connection.link-code", "auto");
        if ("auto".equalsIgnoreCase(configured.trim())) {
            String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            linkCode = raw.substring(0, 4) + "-" + raw.substring(4, 8);
            getConfig().set("connection.link-code", linkCode);
            saveConfig();
        } else {
            linkCode = configured.trim();
        }
    }

    void connectAsync() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String botUrl = getConfig().getString("connection.bot-url", "ws://localhost:8080");
                String name   = getConfig().getString("connection.server-name", "Minecraft Server");
                wsClient = new BotWebSocketClient(new URI(botUrl), this, linkCode, name);
                wsClient.connectBlocking();
            } catch (Exception e) {
                getLogger().warning("[FlowConsoleCmds] Cannot connect: " + e.getMessage() + " – retrying in 10s");
                getServer().getScheduler().runTaskLaterAsynchronously(this, this::connectAsync, 200L);
            }
        });
    }

    void reconnect() {
        if (wsClient != null) {
            try { wsClient.closeBlocking(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        connectAsync();
    }

    void resetCode() {
        String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        linkCode = raw.substring(0, 4) + "-" + raw.substring(4, 8);
        getConfig().set("connection.link-code", linkCode);
        saveConfig();
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.sendHello();
        }
    }

    BotWebSocketClient getWsClient() { return wsClient; }
    String getLinkCode()             { return linkCode; }
    long   getStartTime()            { return startTime; }
}
