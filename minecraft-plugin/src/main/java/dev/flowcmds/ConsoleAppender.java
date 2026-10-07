package dev.flowcmds;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Log4j2 appender attached to the root logger: sees every line the console sees. */
final class ConsoleAppender extends AbstractAppender {

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    // Only after a "/" like in "Player[/2001:db8::1:51234] logged in", so times like 12:34:56 stay untouched
    private static final Pattern IPV6 = Pattern.compile("(?<=/)\\[?(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:%[\\w.]+)?]?");

    /** Logger names Paper prints without a "[name]" prefix (from Paper's log4j2.xml). */
    private static final String[] PAPER_NO_PREFIX = {"net.minecraft.", "Minecraft", "com.mojang.", "com.sk89q.", "ru.tehkode.", "Minecraft.AWE"};

    private final Supplier<Settings> settings;
    private final BridgeClient bridge;
    private final boolean paper;
    /** First line seen after attaching, used to find where logs/latest.log overlaps. */
    private volatile String firstCaptured;

    ConsoleAppender(Supplier<Settings> settings, BridgeClient bridge) {
        super("FlowDiscordConsoleCmds", null, null, true, Property.EMPTY_ARRAY);
        this.settings = settings;
        this.bridge = bridge;
        this.paper = classExists("io.papermc.paper.configuration.Configuration")
                || classExists("com.destroystokyo.paper.PaperConfig");
    }

    String firstCaptured() {
        return firstCaptured;
    }

    @Override
    public void append(LogEvent event) {
        Settings s = settings.get();
        if (!event.getLevel().isMoreSpecificThan(s.minLevel)) return;

        String loggerName = event.getLoggerName() == null ? "" : event.getLoggerName();
        String msg = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        if (msg == null) msg = "";
        msg = Colors.toLegacy(msg);

        // Same prefix rules as Paper's console. Spigot prints no logger names at all
        // (its plugin loggers already put "[PluginName]" into the message).
        if (paper && needsPrefix(loggerName) && !Colors.strip(msg).startsWith("[" + loggerName + "]")) {
            msg = "[" + loggerName + "] " + msg;
        }

        if (event.getThrown() != null) {
            if (s.includeStacktraces) {
                StringWriter sw = new StringWriter();
                event.getThrown().printStackTrace(new PrintWriter(sw));
                msg = msg + "\n" + sw.toString().stripTrailing();
            } else {
                msg = msg + "\n" + event.getThrown();
            }
        }

        if (firstCaptured == null) firstCaptured = Colors.strip(msg).split("\n", 2)[0].trim();

        String out = finish(s, msg);
        if (out != null) bridge.enqueue(new BridgeClient.LogLine(event.getTimeMillis(), event.getLevel().name(), out));
    }

    private static boolean needsPrefix(String loggerName) {
        if (loggerName.isEmpty()) return false;
        for (String p : PAPER_NO_PREFIX) {
            if (loggerName.startsWith(p)) return false;
        }
        return true;
    }

    /** Filters and masks a line. Returns null if it must not be sent. */
    static String finish(Settings s, String legacyColored) {
        String plain = Colors.strip(legacyColored);
        for (Pattern p : s.ignorePatterns) {
            if (p.matcher(plain).find()) return null;
        }
        String out = s.consoleColors ? legacyColored : plain;
        if (s.hideIps) {
            out = IPV4.matcher(out).replaceAll("x.x.x.x");
            out = IPV6.matcher(out).replaceAll("x:x:x:x");
        }
        return out;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ConsoleAppender.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
