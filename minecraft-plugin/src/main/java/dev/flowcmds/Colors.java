package dev.flowcmds;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes console colors to Minecraft legacy codes (§a, §l, §r ...), which the bot turns
 * into Discord ANSI colors. Handles § codes, §x hex colors and ANSI escape sequences.
 */
final class Colors {

    private static final Pattern HEX = Pattern.compile("(?i)§x((?:§[0-9a-f]){6})");
    private static final Pattern LEGACY = Pattern.compile("(?i)§([0-9a-fk-or])");
    private static final Pattern ANY_LEGACY = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern ANSI_SGR = Pattern.compile("\u001B\\[([0-9;]*)m");
    private static final Pattern ANSI_OTHER = Pattern.compile("\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|[@-Z\\\\-_])");

    /** RGB of the 16 legacy colors, index = code 0-f */
    private static final int[] PALETTE = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };
    /** ANSI 30-37 and 90-97 -> legacy code */
    private static final char[] ANSI_NORMAL = {'0', '4', '2', '6', '1', '5', '3', '7'};
    private static final char[] ANSI_BRIGHT = {'8', 'c', 'a', 'e', '9', 'd', 'b', 'f'};

    private Colors() {}

    /** Converts everything to plain legacy codes. */
    static String toLegacy(String s) {
        if (s.indexOf('\u001B') >= 0) s = ansiToLegacy(s);
        if (s.indexOf('§') < 0) return s;
        Matcher m = HEX.matcher(s);
        if (m.find()) {
            StringBuilder b = new StringBuilder();
            do {
                int rgb = Integer.parseInt(m.group(1).replace("§", ""), 16);
                m.appendReplacement(b, "§" + nearest(rgb));
            } while (m.find());
            m.appendTail(b);
            s = b.toString();
        }
        // Lone "§" that isn't a color code stays as it is
        return LEGACY.matcher(s).replaceAll(r -> "§" + r.group(1).toLowerCase());
    }

    static String strip(String s) {
        if (s.indexOf('\u001B') >= 0) s = ANSI_OTHER.matcher(ANSI_SGR.matcher(s).replaceAll("")).replaceAll("");
        return s.indexOf('§') < 0 ? s : ANY_LEGACY.matcher(s).replaceAll("");
    }

    private static String ansiToLegacy(String s) {
        Matcher m = ANSI_SGR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(sgr(m.group(1))));
        }
        m.appendTail(b);
        return ANSI_OTHER.matcher(b).replaceAll("");
    }

    private static String sgr(String params) {
        if (params.isEmpty()) return "§r";
        String[] p = params.split(";");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            int n;
            try {
                n = p[i].isEmpty() ? 0 : Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (n == 0 || n == 39) out.append("§r");
            else if (n == 1) out.append("§l");
            else if (n == 3) out.append("§o");
            else if (n == 4) out.append("§n");
            else if (n == 9) out.append("§m");
            else if (n >= 30 && n <= 37) out.append('§').append(ANSI_NORMAL[n - 30]);
            else if (n >= 90 && n <= 97) out.append('§').append(ANSI_BRIGHT[n - 90]);
            else if ((n == 38 || n == 48) && i + 1 < p.length) {
                boolean fg = n == 38;
                if ("5".equals(p[i + 1]) && i + 2 < p.length) {
                    if (fg) out.append('§').append(nearest(xterm(parse(p[i + 2]))));
                    i += 2;
                } else if ("2".equals(p[i + 1]) && i + 4 < p.length) {
                    int rgb = (parse(p[i + 2]) << 16) | (parse(p[i + 3]) << 8) | parse(p[i + 4]);
                    if (fg) out.append('§').append(nearest(rgb));
                    i += 4;
                }
            }
            // backgrounds and everything else: ignored
        }
        return out.toString();
    }

    private static int parse(String s) {
        try {
            return Math.max(0, Math.min(255, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** xterm 256-color index -> RGB */
    private static int xterm(int n) {
        if (n < 8) return PALETTE[Character.digit(ANSI_NORMAL[n], 16)];
        if (n < 16) return PALETTE[Character.digit(ANSI_BRIGHT[n - 8], 16)];
        if (n < 232) {
            int c = n - 16;
            int r = c / 36, g = (c % 36) / 6, b = c % 6;
            return (cube(r) << 16) | (cube(g) << 8) | cube(b);
        }
        int v = 8 + (n - 232) * 10;
        return (v << 16) | (v << 8) | v;
    }

    private static int cube(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    private static char nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 15;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int pr = PALETTE[i] >> 16 & 0xFF, pg = PALETTE[i] >> 8 & 0xFF, pb = PALETTE[i] & 0xFF;
            long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return Character.forDigit(best, 16);
    }
}
