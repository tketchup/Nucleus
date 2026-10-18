package de.nucleus.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Zahlenformatierung nach Sprache: Deutsch 1.234,56, Englisch 1,234.56. Standard ist Deutsch;
 * die Sprache setzt Messages beim Laden. Ohne Bukkit-Abhängigkeit, damit testbar.
 */
public final class Formats {

    private static volatile DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.GERMANY);

    /** Sprache der Zahlenformate umstellen. */
    public static void locale(Locale locale) {
        symbols = DecimalFormatSymbols.getInstance(locale);
    }

    private Formats() {
    }

    /** Formatiert mit festen Nachkommastellen, z. B. format(1234.5, 2) = "1.234,50". */
    public static String decimal(BigDecimal value, int scale) {
        // DecimalFormat ist nicht threadsicher, daher pro Aufruf eine Instanz
        DecimalFormat format = new DecimalFormat(pattern(scale), symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(scale, RoundingMode.HALF_UP));
    }

    /** Ganzzahl mit Tausenderpunkten. */
    public static String integer(long value) {
        return new DecimalFormat("#,##0", symbols).format(value);
    }

    /** Koordinate mit einer Nachkommastelle. */
    public static String coord(double value) {
        return new DecimalFormat("0.0", symbols).format(value);
    }

    private static String pattern(int scale) {
        return scale <= 0 ? "#,##0" : "#,##0." + "0".repeat(scale);
    }
}
