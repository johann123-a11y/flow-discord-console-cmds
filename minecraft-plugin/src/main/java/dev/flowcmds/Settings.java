package dev.flowcmds;

import org.apache.logging.log4j.Level;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/** Immutable snapshot of config.yml, swapped atomically on reload. */
final class Settings {

    final String botUrl;
    final URI botUri;
    final String serverName;
    final boolean allowCommands;
    final Set<String> blockedCommands;
    final boolean logCommandUsage;
    final boolean commandLogFile;
    final boolean consoleColors;
    final Level minLevel;
    final boolean includeStacktraces;
    final boolean hideIps;
    final List<Pattern> ignorePatterns;
    final int maxQueuedLines;
    final int statusIntervalSeconds;
    final List<String> warnings = new ArrayList<>();

    Settings(FileConfiguration c) {
        botUrl = c.getString("bot-url", "").trim();
        URI uri = null;
        try {
            URI u = URI.create(botUrl);
            if ("ws".equalsIgnoreCase(u.getScheme()) || "wss".equalsIgnoreCase(u.getScheme())) uri = u;
        } catch (IllegalArgumentException ignored) {
        }
        if (uri == null) warnings.add("bot-url '" + botUrl + "' is not a ws:// or wss:// address - not connecting.");
        botUri = uri;

        serverName = c.getString("server-name", "Minecraft Server");
        allowCommands = c.getBoolean("allow-commands", true);
        blockedCommands = c.getStringList("blocked-commands").stream()
                .map(s -> bare(s.trim()))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        logCommandUsage = c.getBoolean("log-command-usage", true);
        commandLogFile = c.getBoolean("command-log-file", true);
        consoleColors = c.getBoolean("console-colors", true);

        // Java-logging names (WARNING, SEVERE) work too
        String lvlName = switch (c.getString("min-level", "INFO").trim().toUpperCase(Locale.ROOT)) {
            case "WARNING" -> "WARN";
            case "SEVERE" -> "ERROR";
            case "FINE", "FINER", "FINEST" -> "DEBUG";
            default -> c.getString("min-level", "INFO").trim();
        };
        Level lvl = Level.toLevel(lvlName, null);
        if (lvl == null) {
            warnings.add("Unknown min-level '" + c.getString("min-level") + "', using INFO");
            lvl = Level.INFO;
        }
        minLevel = lvl;
        includeStacktraces = c.getBoolean("include-stacktraces", true);
        hideIps = c.getBoolean("hide-ips", true);

        List<Pattern> patterns = new ArrayList<>();
        for (String p : c.getStringList("ignore-patterns")) {
            try {
                patterns.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                warnings.add("Invalid ignore-pattern '" + p + "': " + e.getDescription());
            }
        }
        ignorePatterns = List.copyOf(patterns);
        maxQueuedLines = Math.max(100, c.getInt("max-queued-lines", 5000));
        statusIntervalSeconds = Math.max(5, c.getInt("status-interval-seconds", 30));
    }

    /**
     * Returns the blocked command name if the command line is not allowed, else null.
     * Also checks commands hidden in "execute ... run <command>" and every alias of a
     * plugin command (aliasesOf: name -> real name + aliases, main thread only).
     */
    String blockedBy(String commandLine, Function<String, Collection<String>> aliasesOf) {
        if (blockedCommands.isEmpty()) return null;
        String[] tokens = commandLine.trim().split("\\s+");
        boolean inExecute = false;
        for (int i = 0; i < tokens.length; i++) {
            boolean commandStart = i == 0 || (inExecute && tokens[i - 1].equalsIgnoreCase("run"));
            if (!commandStart) continue;
            String name = bare(tokens[i]);
            if (blockedCommands.contains(name)) return name;
            for (String alias : aliasesOf.apply(name)) {
                String a = bare(alias);
                if (blockedCommands.contains(a)) return a;
            }
            if (name.equals("execute")) inExecute = true;
        }
        return null;
    }

    /** "/Minecraft:OP" -> "op" */
    private static String bare(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.startsWith("/")) t = t.substring(1);
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }
}
