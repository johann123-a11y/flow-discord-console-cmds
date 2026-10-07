package de.flowdev.consolecmds;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class BotWebSocketClient extends WebSocketClient {

    private final FlowDiscordConsoleCmds plugin;
    private final String serverName;
    private final Gson gson = new Gson();
    private volatile boolean reconnecting = false;

    // Holds lines produced while the bot is unreachable
    private final Deque<String> offlineBuffer = new ArrayDeque<>();
    private static final int MAX_BUFFER = 500;

    public BotWebSocketClient(URI uri, FlowDiscordConsoleCmds plugin, String linkCode, String serverName) {
        super(uri);
        this.plugin     = plugin;
        this.serverName = serverName;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        plugin.getLogger().info("[FlowConsoleCmds] Connected to bot");
        sendHello();
        flushOfflineBuffer();
    }

    public void sendHello() {
        JsonObject pkt = new JsonObject();
        pkt.addProperty("type",       "hello");
        pkt.addProperty("code",       plugin.getLinkCode());
        pkt.addProperty("name",       serverName);
        pkt.addProperty("version",    Bukkit.getVersion());
        pkt.addProperty("players",    Bukkit.getOnlinePlayers().size());
        pkt.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        safeSend(gson.toJson(pkt));
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonObject pkt  = gson.fromJson(raw, JsonObject.class);
            String     type = pkt.has("type") ? pkt.get("type").getAsString() : "";

            switch (type) {
                case "exec":
                    handleExec(pkt.has("data") ? pkt.get("data").getAsString().trim() : "");
                    break;

                case "linked":
                    plugin.getLogger().info("[FlowConsoleCmds] Linked to a Discord channel");
                    break;

                case "unlinked":
                    plugin.getLogger().info("[FlowConsoleCmds] Not linked to any Discord channel – run /link " + plugin.getLinkCode());
                    break;

                case "ping":
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type",       "pong");
                    pong.addProperty("players",    Bukkit.getOnlinePlayers().size());
                    pong.addProperty("maxPlayers", Bukkit.getMaxPlayers());
                    safeSend(gson.toJson(pong));
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[FlowConsoleCmds] Packet error: " + e.getMessage());
        }
    }

    private void handleExec(String cmd) {
        if (cmd.isEmpty()) return;

        if (!plugin.getConfig().getBoolean("commands.allow-commands", true)) {
            sendLog("[FlowConsoleCmds] Command rejected: allow-commands is false");
            return;
        }

        List<String> blocked = plugin.getConfig().getStringList("commands.blocked-commands");
        String       base    = cmd.split(" ")[0].toLowerCase();
        for (String b : blocked) {
            if (base.equals(b.toLowerCase())) {
                sendLog("[FlowConsoleCmds] Command blocked: " + cmd);
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        );
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        plugin.getLogger().info("[FlowConsoleCmds] Disconnected – reconnecting in 10s");
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        plugin.getLogger().warning("[FlowConsoleCmds] Error: " + ex.getMessage());
    }

    public void sendLog(String line) {
        if (isOpen()) {
            JsonObject pkt = new JsonObject();
            pkt.addProperty("type", "log");
            pkt.addProperty("data", line);
            safeSend(gson.toJson(pkt));
        } else {
            synchronized (offlineBuffer) {
                if (offlineBuffer.size() >= MAX_BUFFER) offlineBuffer.poll();
                offlineBuffer.offer(line);
            }
        }
    }

    public void sendStats(JsonObject stats) {
        stats.addProperty("type", "stats");
        if (isOpen()) safeSend(gson.toJson(stats));
    }

    private void flushOfflineBuffer() {
        synchronized (offlineBuffer) {
            while (!offlineBuffer.isEmpty()) {
                JsonObject pkt = new JsonObject();
                pkt.addProperty("type", "log");
                pkt.addProperty("data", offlineBuffer.poll());
                safeSend(gson.toJson(pkt));
            }
        }
    }

    private void safeSend(String json) {
        try { send(json); } catch (Exception ignored) {}
    }

    private void scheduleReconnect() {
        if (reconnecting) return;
        reconnecting = true;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnecting = false;
            plugin.connectAsync();
        }, 200L);
    }
}
