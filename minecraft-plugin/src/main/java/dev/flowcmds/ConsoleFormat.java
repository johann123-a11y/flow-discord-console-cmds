package dev.flowcmds;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a log line exactly like Paper's console, "[12:34:56 WARN]: message",
 * with ANSI colors for Discord's ```ansi blocks: WARN yellow, ERROR red, § colors kept.
 */
final class ConsoleFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String ESC = "\u001B";
    private static final Pattern LEGACY = Pattern.compile("§([0-9a-fk-or])");
    /** Minecraft color -> Discord ANSI color (Discord knows only 8 colors) */
    private static final String[] MC_TO_ANSI = {
            "30", "34", "32", "36", "31", "35", "33", "37", "30", "34", "32", "36", "31", "35", "33", "37",
    };

    private ConsoleFormat() {}

    static String render(BridgeClient.LogLine l) {
        String base = switch (l.level()) {
            case "WARN" -> "33";
            case "ERROR", "FATAL" -> "31";
            case "DEBUG", "TRACE" -> "30";
            default -> null;
        };
        String[] parts = l.text().replace(ESC, "")
                .replace("```", "`​``") // a log line must not close the bot's code block
                .split("\r?\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('\n');
            String line = (i == 0 ? "[" + TIME.format(Instant.ofEpochMilli(l.time())) + " " + l.level() + "]: " : "") + parts[i];
            out.append(colorize(line, base));
        }
        return out.toString();
    }

    /** §-codes -> ANSI; §r goes back to the line's level color. */
    private static String colorize(String line, String base) {
        String reset = base != null ? ESC + "[0;" + base + "m" : ESC + "[0m";
        boolean styled = base != null;
        StringBuilder body = new StringBuilder();
        Matcher m = LEGACY.matcher(line);
        int last = 0;
        while (m.find()) {
            body.append(line, last, m.start());
            last = m.end();
            char c = m.group(1).charAt(0);
            int digit = Character.digit(c, 16);
            if (digit >= 0 && c <= 'f') {
                body.append(ESC).append("[0;").append(MC_TO_ANSI[digit]).append('m');
                styled = true;
            } else if (c == 'l') {
                body.append(ESC).append("[1m");
                styled = true;
            } else if (c == 'n') {
                body.append(ESC).append("[4m");
                styled = true;
            } else if (c == 'r') {
                body.append(reset);
            } // §k §m §o: Discord can't show them
        }
        body.append(line, last, line.length());
        if (!styled) return body.toString();
        return (base != null ? ESC + "[0;" + base + "m" : "") + body + ESC + "[0m";
    }
}
