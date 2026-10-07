package dev.flowcmds;

import org.apache.logging.log4j.Level;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugin is loaded after the server already printed its first lines
 * ("Starting minecraft server version ...", "Loading properties", ...).
 * Those are read back from logs/latest.log so Discord shows the console from the very first line.
 */
final class LogBackfill {

    // Spigot and Paper file format: [12:34:56] [Server thread/INFO]: message
    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d{2}):(\\d{2}):(\\d{2})] \\[(.*?)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)]: ?(.*)$");
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private record Entry(long time, String level, StringBuilder text) {}

    private LogBackfill() {}

    /**
     * @param firstCaptured first line the appender captured; everything from there on is already queued.
     *                      Asked AFTER reading the file: a line captured later can't be in the file yet.
     *                      null = appender has not seen anything yet, take the whole file.
     */
    static List<BridgeClient.LogLine> read(File file, Settings s, Supplier<String> firstCaptured, int maxLines) throws IOException {
        if (!file.isFile()) return List.of();
        String content = readTail(file);
        String stopAt = firstCaptured.get();

        List<Entry> entries = new ArrayList<>();
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        for (String raw : content.split("\r?\n")) {
            Matcher m = LINE.matcher(raw);
            if (m.matches()) {
                LocalTime t = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                long ms = today.atTime(t).atZone(zone).toInstant().toEpochMilli();
                if (ms > now + 60_000) ms -= 86_400_000L; // line from before midnight
                entries.add(new Entry(ms, m.group(5), new StringBuilder(m.group(6))));
            } else if (!entries.isEmpty()) {
                entries.get(entries.size() - 1).text().append('\n').append(raw); // stack trace line
            }
        }

        // Cut where the live capture begins, so no line is sent twice
        int end = entries.size();
        if (stopAt != null && !stopAt.isEmpty()) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                String first = entries.get(i).text().toString().split("\n", 2)[0].trim();
                if (first.equals(stopAt)) {
                    end = i;
                    break;
                }
            }
        }

        List<BridgeClient.LogLine> out = new ArrayList<>();
        for (int i = Math.max(0, end - maxLines * 2); i < end; i++) {
            Entry e = entries.get(i);
            Level lvl = Level.toLevel(e.level(), Level.INFO);
            if (!lvl.isMoreSpecificThan(s.minLevel)) continue;
            String text = e.text().toString();
            if (!s.includeStacktraces) text = text.split("\n", 2)[0];
            String fin = ConsoleAppender.finish(s, text);
            if (fin != null) out.add(new BridgeClient.LogLine(e.time(), e.level(), fin));
        }
        return out.size() > maxLines ? out.subList(out.size() - maxLines, out.size()) : out;
    }

    private static String readTail(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - MAX_BYTES);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8); // invalid bytes become \uFFFD instead of failing
            if (start > 0) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1); // drop the partial first line
            }
            return s;
        }
    }
}
