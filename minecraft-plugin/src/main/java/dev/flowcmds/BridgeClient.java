package dev.flowcmds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * WebSocket connection to the flow-discord-console-cmds bot. Everything (connect, send, handle)
 * runs on one thread, so the socket is never written to concurrently.
 *
 * Protocol of the bot (JSON text frames):
 *   plugin -> bot: hello {code, name, version, players, maxPlayers}
 *                  log {data: "one console line"}
 *                  stats {players, maxPlayers, playerNames, tps, ram, uptime, chunks, entities, plugins}
 *                  pong {players, maxPlayers}
 *   bot -> plugin: linked | unlinked (answer to hello, and on /link /unlink)
 *                  exec {data: "command"}
 *                  ping
 * The bot routes by link code; lines are always sent, the bot posts them to the linked channel(s)
 * and keeps the last 30 to show when someone links.
 */
final class BridgeClient implements WebSocket.Listener {

    record LogLine(long time, String level, String text) {}

    interface Handler {
        void onCommand(String command);
        void onStatsRequest();
    }

    private static final int MAX_EVENT_CHARS = 30_000;          // one log event incl. stack trace
    private static final long MAX_QUEUED_CHARS = 8_000_000;     // ~16 MB RAM at most while the bot is down
    private static final int MAX_LINES_PER_FLUSH = 500;
    private static final long LOGIN_TIMEOUT_MS = 15_000;
    private static final long PING_INTERVAL_MS = 30_000;        // the bot sends no heartbeat, so we ping it
    private static final long SILENCE_TIMEOUT_MS = 90_000;
    private static final int CLOSE_REPLACED = 4004;             // bot: same code connected again

    private final Supplier<Settings> settings;
    private final Supplier<String> linkCode;
    private final String serverVersion;
    private final Logger log;
    private final Handler handler;

    /** Guards queue, queuedChars and dropped. */
    private final Object lock = new Object();
    private final ArrayDeque<LogLine> queue = new ArrayDeque<>();
    private long queuedChars;
    private int dropped;

    private final ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "FlowDiscordConsoleCmds-Bridge");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // Only touched on the exec thread
    private WebSocket ws;
    private boolean connecting;
    private int connectionId;
    private int failedAttempts;
    private ScheduledFuture<?> reconnectTask;
    private boolean shuttingDown;
    private long connectedAt;
    private long lastPingSent;
    private final StringBuilder incoming = new StringBuilder();

    // Read from other threads
    private volatile long lastReceived;
    private volatile String state = "not started";
    private volatile boolean authed;   // bot answered our hello
    private volatile boolean linked;
    private volatile String lastError;
    private volatile long sentLines;
    private volatile int players;
    private volatile int maxPlayers;

    BridgeClient(Supplier<Settings> settings, Supplier<String> linkCode, String serverVersion, Logger log, Handler handler) {
        this.settings = settings;
        this.linkCode = linkCode;
        this.serverVersion = serverVersion;
        this.log = log;
        this.handler = handler;
        exec.setRemoveOnCancelPolicy(true);
    }

    // ------------------------------------------------------------------ public API (any thread)

    void start() {
        run(this::connect);
        exec.scheduleWithFixedDelay(this::safeFlush, 500, 500, TimeUnit.MILLISECONDS);
        exec.scheduleWithFixedDelay(this::watchdog, 5, 5, TimeUnit.SECONDS);
    }

    /** Called by the log appender on whatever thread logged. Never blocks for long. */
    void enqueue(LogLine line) {
        LogLine l = cap(line);
        synchronized (lock) {
            queue.addLast(l);
            queuedChars += l.text().length();
            trimQueue();
        }
    }

    /** Lines logged before the plugin was loaded. They go in front of everything captured live. */
    void backfill(List<LogLine> lines) {
        synchronized (lock) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                LogLine l = cap(lines.get(i));
                queue.addFirst(l);
                queuedChars += l.text().length();
            }
            trimQueue();
        }
    }

    void reconnect() {
        run(() -> {
            failedAttempts = 0;
            if (reconnectTask != null) reconnectTask.cancel(false);
            reconnectTask = null;
            closeSocket("Reconnecting");
            connect();
        });
    }

    void setPlayerCounts(int online, int max) {
        players = online;
        maxPlayers = max;
    }

    void sendStats(JsonObject stats) {
        stats.addProperty("type", "stats");
        run(() -> {
            if (authed) send(stats);
        });
    }

    void shutdown() {
        run(() -> {
            shuttingDown = true;
            if (reconnectTask != null) reconnectTask.cancel(false);
            for (int i = 0; i < 10 && authed && queuedLines() > 0; i++) flush();
            closeSocket("Server stopping");
        });
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    String state() { return state; }
    boolean isConnected() { return authed; }
    boolean isLinked() { return linked; }
    String lastError() { return lastError; }
    long sentLines() { return sentLines; }

    int queuedLines() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** exec.execute that doesn't throw once the plugin is shutting down. */
    private void run(Runnable r) {
        try {
            exec.execute(r);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static LogLine cap(LogLine l) {
        if (l.text().length() <= MAX_EVENT_CHARS) return l;
        return new LogLine(l.time(), l.level(), l.text().substring(0, MAX_EVENT_CHARS) + "\n... (cut, see logs/latest.log)");
    }

    /** Caller holds lock. Drops the oldest lines when the bot is offline for long. */
    private void trimQueue() {
        int max = settings.get().maxQueuedLines;
        while (queue.size() > max || queuedChars > MAX_QUEUED_CHARS) {
            LogLine old = queue.pollFirst();
            if (old == null) break;
            queuedChars -= old.text().length();
            dropped++;
        }
    }

    // ------------------------------------------------------------------ connection (exec thread)

    private void connect() {
        reconnectTask = null;
        if (shuttingDown || ws != null || connecting) return;
        Settings s = settings.get();
        if (s.botUri == null) {
            state = "no valid bot-url in config.yml";
            return;
        }
        state = "connecting to " + s.botUrl;
        connecting = true;
        int myId = ++connectionId;
        try {
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(s.botUri, this)
                    .whenComplete((socket, err) -> run(() -> {
                        if (myId != connectionId || shuttingDown) { // reconnect()/shutdown happened meanwhile
                            if (socket != null) socket.abort();
                            return;
                        }
                        connecting = false;
                        if (err != null) {
                            lastError = rootMessage(err);
                            onDisconnected();
                            return;
                        }
                        ws = socket;
                        connectedAt = System.currentTimeMillis();
                        lastReceived = connectedAt;
                        lastPingSent = connectedAt;
                        state = "connected, waiting for the bot";
                        sendHello();
                    }));
        } catch (RuntimeException e) { // e.g. bot-url with a #fragment
            connecting = false;
            lastError = rootMessage(e);
            scheduleReconnect();
        }
    }

    private void sendHello() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("code", linkCode.get());
        o.addProperty("name", settings.get().serverName);
        o.addProperty("version", serverVersion);
        o.addProperty("players", players);
        o.addProperty("maxPlayers", maxPlayers);
        send(o);
    }

    /** Keeps the connection alive and detects a bot that died without closing it. */
    private void watchdog() {
        try {
            WebSocket w = ws;
            if (w == null) return;
            long now = System.currentTimeMillis();
            if (!authed && now - connectedAt > LOGIN_TIMEOUT_MS) {
                lastError = "bot did not answer (is bot-url really the flow-discord-console-cmds bot?)";
            } else if (now - lastReceived > SILENCE_TIMEOUT_MS) {
                lastError = "bot stopped answering for " + SILENCE_TIMEOUT_MS / 1000 + "s";
            } else {
                if (now - lastPingSent >= PING_INTERVAL_MS) {
                    lastPingSent = now;
                    w.sendPing(ByteBuffer.allocate(0)).get(10, TimeUnit.SECONDS); // ws answers with a pong
                }
                return;
            }
            w.abort();
            onDisconnected();
        } catch (Exception e) {
            lastError = rootMessage(e);
            WebSocket w = ws;
            if (w != null) {
                w.abort();
                onDisconnected();
            }
        }
    }

    private void onDisconnected() {
        boolean wasAuthed = authed;
        ws = null;
        authed = false;
        incoming.setLength(0);
        if (shuttingDown) return;
        if (wasAuthed) log.warning("Lost connection to the Discord bot (" + lastError + ") - reconnecting...");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (reconnectTask != null || shuttingDown) return;
        failedAttempts++;
        long delay = Math.min(60, 1L << Math.min(failedAttempts, 6));
        state = "disconnected (" + (lastError == null ? "?" : lastError) + "), retry in " + delay + "s";
        if (failedAttempts == 3) {
            log.warning("Can't reach the Discord bot at " + settings.get().botUrl + ": " + lastError
                    + " (still retrying, see /flowcmds status)");
        }
        try {
            reconnectTask = exec.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void closeSocket(String reason) {
        WebSocket w = ws;
        ws = null;
        connecting = false;
        connectionId++;
        authed = false;
        incoming.setLength(0);
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, reason).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            w.abort();
        }
    }

    /** Sends one frame and waits for it, so frames never overlap. Exec thread only. */
    private boolean send(JsonObject o) {
        WebSocket w = ws;
        if (w == null) return false;
        try {
            w.sendText(o.toString(), true).get(15, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            lastError = rootMessage(e);
            w.abort();
            if (ws == w) onDisconnected();
            return false;
        }
    }

    // ------------------------------------------------------------------ incoming (WebSocket.Listener)

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        lastReceived = System.currentTimeMillis();
        String text = data.toString();
        run(() -> {
            if (webSocket != ws) return;
            incoming.append(text);
            if (!last) return;
            String msg = incoming.toString();
            incoming.setLength(0);
            try {
                handle(JsonParser.parseString(msg).getAsJsonObject());
            } catch (RuntimeException e) {
                log.warning("Could not handle message from bot: " + e);
            }
        });
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        lastReceived = System.currentTimeMillis(); // the JDK answers with a pong by itself
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        lastReceived = System.currentTimeMillis();
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        run(() -> {
            if (webSocket != ws) return;
            webSocket.abort(); // don't leave the connection half-open
            if (statusCode == CLOSE_REPLACED) {
                lastError = "another server connected with the same link code";
                log.warning("Another Minecraft server connected to the bot with the SAME link code "
                        + "(plugins/FlowDiscordConsoleCmds/data.yml was copied?). They kick each other off. "
                        + "Run '/flowcmds reset confirm' on the copy to give it its own code.");
                failedAttempts = Math.max(failedAttempts, 5); // -> wait 60s instead of fighting every 2s
            } else {
                lastError = "closed by bot (" + statusCode + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")";
            }
            onDisconnected();
        });
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        run(() -> {
            if (webSocket != ws) return;
            lastError = rootMessage(error);
            onDisconnected();
        });
    }

    private void handle(JsonObject m) {
        switch (str(m, "type")) {
            case "linked" -> onLinked();
            case "unlinked" -> onUnlinked();
            case "exec" -> handler.onCommand(str(m, "data"));
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                pong.addProperty("players", players);
                pong.addProperty("maxPlayers", maxPlayers);
                send(pong);
                handler.onStatsRequest(); // someone ran /status: send fresh numbers
            }
            default -> { /* unknown message from a newer bot - ignore */ }
        }
    }

    /** First "linked"/"unlinked" after hello = the bot accepted us. */
    private boolean markAuthed() {
        boolean first = !authed;
        authed = true;
        if (first) {
            failedAttempts = 0;
            lastError = null;
        }
        return first;
    }

    private void onLinked() {
        boolean first = markAuthed();
        boolean wasLinked = linked;
        linked = true;
        state = "connected, linked to Discord";
        if (first) log.info("Connected to the Discord bot - the console is live in Discord.");
        else if (!wasLinked) log.info("This server is now linked to a Discord channel.");
    }

    private void onUnlinked() {
        boolean first = markAuthed();
        boolean wasLinked = linked;
        linked = false;
        state = "connected, NOT linked yet - code " + linkCode.get();
        if (wasLinked && !first) log.warning("This server was unlinked from its Discord channel.");
        if (first || wasLinked) printLinkCode();
    }

    void printLinkCode() {
        String code = linkCode.get();
        String bar = "==========================================================";
        log.info(bar);
        log.info(" Discord console link code:   " + code);
        log.info(" In the Discord channel that should become the console run:");
        log.info("   /link " + code);
        log.info(bar);
    }

    // ------------------------------------------------------------------ log streaming (exec thread)

    private void safeFlush() {
        try {
            if (authed) flush();
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /** The bot takes one line per message. */
    private void flush() {
        for (int n = 0; n < MAX_LINES_PER_FLUSH && ws != null; n++) {
            LogLine l;
            synchronized (lock) {
                if (dropped > 0) {
                    LogLine notice = new LogLine(System.currentTimeMillis(), "WARN",
                            "... " + dropped + " console lines skipped (the bot was unreachable for too long)");
                    queue.addFirst(notice);
                    queuedChars += notice.text().length();
                    dropped = 0;
                }
                l = queue.pollFirst();
                if (l == null) return;
                queuedChars -= l.text().length();
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "log");
            o.addProperty("data", ConsoleFormat.render(l));
            if (!send(o)) {
                synchronized (lock) { // put it back so nothing is lost while reconnecting
                    queue.addFirst(l);
                    queuedChars += l.text().length();
                    trimQueue();
                }
                return;
            }
            sentLines++;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
