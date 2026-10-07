package de.flowdev.consolecmds;

import java.text.MessageFormat;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class ConsoleLogHandler extends Handler {

    private final FlowDiscordConsoleCmds plugin;

    private static final Pattern ANSI   = Pattern.compile("\\x1B\\[[;\\d]*m");
    private static final Pattern COLOR  = Pattern.compile("§[0-9a-fklmnorA-FKLMNOR]");
    private static final Pattern IP     = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    public ConsoleLogHandler(FlowDiscordConsoleCmds plugin) {
        this.plugin = plugin;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) return;

        String minLevelName = plugin.getConfig().getString("console.min-level", "INFO");
        Level  minLevel;
        try { minLevel = Level.parse(minLevelName.toUpperCase()); }
        catch (Exception e) { minLevel = Level.INFO; }

        if (record.getLevel().intValue() < minLevel.intValue()) return;

        String loggerName = record.getLoggerName();
        if (loggerName != null) {
            List<String> ignored = plugin.getConfig().getStringList("console.ignored-loggers");
            for (String prefix : ignored) {
                if (loggerName.startsWith(prefix)) return;
            }
        }

        String message = resolveMessage(record);
        if (message == null || message.trim().isEmpty()) return;

        message = ANSI.matcher(message).replaceAll("");
        message = COLOR.matcher(message).replaceAll("");

        if (plugin.getConfig().getBoolean("console.hide-ips", true)) {
            message = IP.matcher(message).replaceAll("[hidden]");
        }

        String line = "[" + record.getLevel().getName() + "] " + message.trim();

        BotWebSocketClient ws = plugin.getWsClient();
        if (ws != null) ws.sendLog(line);
    }

    @Override public void flush() {}
    @Override public void close() throws SecurityException {}

    private String resolveMessage(LogRecord record) {
        try {
            String   msg    = record.getMessage();
            Object[] params = record.getParameters();
            if (msg != null && params != null && params.length > 0) {
                msg = MessageFormat.format(msg, params);
            }
            return msg;
        } catch (Exception e) {
            return record.getMessage();
        }
    }
}
