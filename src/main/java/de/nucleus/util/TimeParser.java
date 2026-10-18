package de.nucleus.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parst Zeitangaben wie "1d2h30m" oder "90s" und formatiert Dauern lesbar auf Deutsch.
 *
 * <p>Einheiten: s (Sekunden), m (Minuten), h (Stunden), d (Tage), w (Wochen),
 * mo (Monate = 30 Tage), y (Jahre = 365 Tage). Groß-/Kleinschreibung egal.
 */
public final class TimeParser {

    private TimeParser() {
    }

    /**
     * @return die Dauer, oder leer bei ungültiger Eingabe (leer, unbekannte Einheit, Zahl ohne Einheit, 0)
     */
    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        long totalSeconds = 0;
        int i = 0;
        while (i < s.length()) {
            int numStart = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (numStart == i) {
                return Optional.empty(); // Einheit ohne Zahl
            }
            long amount;
            try {
                amount = Long.parseLong(s.substring(numStart, i));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            int unitStart = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) {
                i++;
            }
            long unitSeconds = switch (s.substring(unitStart, i)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                case "w" -> 604_800L;
                case "mo" -> 2_592_000L;
                case "y" -> 31_536_000L;
                default -> -1L; // auch "" (Zahl ohne Einheit)
            };
            if (unitSeconds < 0) {
                return Optional.empty();
            }
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, unitSeconds));
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
        }
        return totalSeconds > 0 ? Optional.of(Duration.ofSeconds(totalSeconds)) : Optional.empty();
    }

    /** Namen der Zeiteinheiten (Einzahl/Mehrzahl) für eine Sprache. */
    public record Units(String day, String days, String hour, String hours,
                        String minute, String minutes, String second, String seconds) {
        public static final Units GERMAN = new Units("Tag", "Tage", "Stunde", "Stunden", "Minute", "Minuten", "Sekunde", "Sekunden");
        public static final Units ENGLISH = new Units("day", "days", "hour", "hours", "minute", "minutes", "second", "seconds");
    }

    /** Deutsch, siehe {@link #format(Duration, Units)}. */
    public static String format(Duration duration) {
        return format(duration, Units.GERMAN);
    }

    /**
     * Formatiert z. B. 9000 s als "2 Stunden, 30 Minuten". Angefangene Sekunden werden aufgerundet,
     * damit ein Cooldown von 0,3 s nicht als "0 Sekunden" erscheint.
     */
    public static String format(Duration duration, Units units) {
        long millis = Math.max(0, duration.toMillis());
        long seconds = (millis + 999) / 1000;
        if (seconds == 0) {
            return "0 " + units.seconds();
        }
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        List<String> parts = new ArrayList<>(4);
        add(parts, days, units.day(), units.days());
        add(parts, hours, units.hour(), units.hours());
        add(parts, minutes, units.minute(), units.minutes());
        add(parts, seconds, units.second(), units.seconds());
        return String.join(", ", parts);
    }

    /** Kurzform wie bei der Eingabe, z. B. 26 Stunden → "1d2h". Sprachunabhängig (für Protokolle). */
    public static String compact(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) {
            return "0s";
        }
        StringBuilder out = new StringBuilder();
        long[] sizes = {86_400, 3_600, 60, 1};
        String[] units = {"d", "h", "m", "s"};
        for (int i = 0; i < sizes.length; i++) {
            if (seconds >= sizes[i]) {
                out.append(seconds / sizes[i]).append(units[i]);
                seconds %= sizes[i];
            }
        }
        return out.toString();
    }

    private static void add(List<String> parts, long value, String singular, String plural) {
        if (value > 0) {
            parts.add(value + " " + (value == 1 ? singular : plural));
        }
    }
}
