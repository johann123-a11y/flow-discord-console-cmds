package dev.flowcmds;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The first version of this plugin (package de.flowdev.consolecmds) used the same data folder
 * with a different config layout ("connection: bot-url: ..."). This converts such a config,
 * and keeps its link code as server ID so Discord channels that are already linked keep working.
 */
final class ConfigMigration {

    private ConfigMigration() {}

    /** @return true if an old config was converted */
    static boolean migrate(File dataFolder, Supplier<InputStream> defaultConfig) throws IOException {
        File file = new File(dataFolder, "config.yml");
        if (!file.isFile()) return false;
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        if (!old.isConfigurationSection("connection") || old.contains("bot-url")) return false;

        File backup = new File(dataFolder, "config-old.yml");
        Files.move(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (InputStream in = defaultConfig.get()) {
            if (in == null) throw new IOException("config.yml missing in the plugin jar");
            Files.copy(in, file.toPath());
        }

        YamlConfiguration c = YamlConfiguration.loadConfiguration(file); // keeps the comments (1.18.1+)
        copy(old, "connection.bot-url", c, "bot-url");
        copy(old, "connection.server-name", c, "server-name");
        copy(old, "commands.allow-commands", c, "allow-commands");
        copy(old, "commands.blocked-commands", c, "blocked-commands");
        copy(old, "console.hide-ips", c, "hide-ips");
        if (old.isString("console.min-level")) {
            c.set("min-level", switch (old.getString("console.min-level").trim().toUpperCase(Locale.ROOT)) {
                case "WARNING" -> "WARN";
                case "SEVERE" -> "ERROR";
                case "FINE", "FINER", "FINEST" -> "DEBUG";
                default -> old.getString("console.min-level").trim().toUpperCase(Locale.ROOT);
            });
        }
        c.save(file);

        String code = old.getString("connection.link-code", "").trim().toUpperCase(Locale.ROOT);
        if (code.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) {
            File dataFile = new File(dataFolder, "data.yml");
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            if (!data.contains("server-id") && !data.contains("link-code")) {
                data.set("server-id", code);
                data.save(dataFile);
            }
        }
        return true;
    }

    private static void copy(YamlConfiguration from, String fromPath, YamlConfiguration to, String toPath) {
        if (from.contains(fromPath)) to.set(toPath, from.get(fromPath));
    }
}
