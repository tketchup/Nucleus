package de.nucleus.util;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Minecraft-Tageszeit: Tick 0 entspricht 6:00 Uhr, ein Tag hat 24000 Ticks. Ohne Bukkit, damit testbar.
 */
public final class GameTime {

    private GameTime() {
    }

    /** Wörter (day, noon, sunset, night, midnight, sunrise), Ticks (0-23999) oder "HH:MM". */
    public static OptionalLong parse(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim();
        switch (s) {
            case "day" -> { return OptionalLong.of(1000); }
            case "noon" -> { return OptionalLong.of(6000); }
            case "sunset" -> { return OptionalLong.of(12000); }
            case "night" -> { return OptionalLong.of(13000); }
            case "midnight" -> { return OptionalLong.of(18000); }
            case "sunrise" -> { return OptionalLong.of(23000); }
            default -> { }
        }
        if (s.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(s.substring(0, s.indexOf(':')));
            int m = Integer.parseInt(s.substring(s.indexOf(':') + 1));
            if (h > 23 || m > 59) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(((h + 18) % 24) * 1000L + m * 1000L / 60);
        }
        if (s.matches("\\d{1,5}")) {
            long t = Long.parseLong(s);
            return t < 24000 ? OptionalLong.of(t) : OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    /** Ticks als Uhrzeit, z. B. 1000 → "07:00". */
    public static String clock(long ticks) {
        long t = ((ticks % 24000) + 24000) % 24000;
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
