package dev.flowcmds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Audit trail of every command sent from Discord: plugins/FlowDiscordConsoleCmds/commands.log */
final class CommandLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowDiscordConsoleCmds-CommandLog");
        t.setDaemon(true);
        return t;
    });
    private boolean warned;

    CommandLog(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** result: OK, FAILED, BLOCKED, DISABLED ... */
    void write(String user, String command, String result, String detail) {
        String line = "[" + LocalDateTime.now().format(TIME) + "] " + user + " | " + result + " | /" + command
                + (detail == null || detail.isEmpty() ? "" : " | " + detail) + System.lineSeparator();
        try {
            io.execute(() -> {
                try {
                    Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    if (!warned) {
                        warned = true;
                        log.warning("Could not write " + file.getFileName() + ": " + e);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // plugin is shutting down
        }
    }

    void close() {
        io.shutdown();
        try {
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
