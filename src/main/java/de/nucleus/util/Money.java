package de.nucleus.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Geldbeträge als {@code long} in der kleinsten Einheit (z. B. Cent). Parsen und Formatieren laufen
 * über BigDecimal, nie über float/double. Ohne Bukkit-Abhängigkeit, damit testbar.
 */
public final class Money {

    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    private Money() {
    }

    /**
     * Liest einen Betrag: "12", "12.5", "12,50", "1k" (Tausend), "2,5m" (Million). Komma und Punkt
     * sind beide Dezimaltrenner, Tausendertrennzeichen gibt es nicht.
     *
     * @return Betrag in kleinster Einheit; leer bei ungültiger Eingabe, negativem Wert, mehr
     *         Nachkommastellen als erlaubt oder Überlauf
     */
    public static OptionalLong parse(String input, int decimals) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        BigDecimal factor = BigDecimal.ONE;
        if (s.endsWith("k")) {
            factor = BigDecimal.valueOf(1_000);
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            factor = BigDecimal.valueOf(1_000_000);
            s = s.substring(0, s.length() - 1);
        }
        if (!NUMBER.matcher(s).matches() || s.length() > 30) {
            return OptionalLong.empty();
        }
        BigDecimal value = new BigDecimal(s).multiply(factor).stripTrailingZeros();
        if (Math.max(0, value.scale()) > decimals) {
            return OptionalLong.empty(); // z. B. 0,001 bei 2 Nachkommastellen
        }
        try {
            return OptionalLong.of(value.movePointRight(decimals).longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** Der Betrag 1 (eine ganze Einheit) in kleinster Einheit, z. B. 100 bei 2 Nachkommastellen. */
    public static long one(int decimals) {
        long value = 1;
        for (int i = 0; i < decimals; i++) {
            value *= 10;
        }
        return value;
    }

    /** Betrag als BigDecimal, z. B. 123456 bei 2 Nachkommastellen = 1234.56. */
    public static BigDecimal toDecimal(long amount, int decimals) {
        return BigDecimal.valueOf(amount, decimals);
    }

    /**
     * BigDecimal (z. B. aus der Vault-API) in kleinste Einheit. Überzählige Nachkommastellen werden
     * nach {@code rounding} behandelt (DOWN für Einzahlungen, UP für Abbuchungen: nie zugunsten des
     * Kontos gerundet).
     *
     * @return leer bei negativem Betrag oder Überlauf
     */
    public static OptionalLong fromDecimal(BigDecimal value, int decimals, java.math.RoundingMode rounding) {
        if (value == null || value.signum() < 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(value.setScale(decimals, rounding).movePointRight(decimals).longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** Formatiert z. B. format(123456, 2) = "1.234,56". */
    public static String format(long amount, int decimals) {
        return Formats.decimal(BigDecimal.valueOf(amount, decimals), decimals);
    }

    /**
     * Rechnet Beträge um, wenn sich die Zahl der Nachkommastellen ändert (z. B. 2 → 0 beim Laden
     * einer alten Datei). Beim Verkleinern wird abgerundet, beim Vergrößern bei Überlauf gedeckelt.
     */
    public static long rescale(long amount, int fromDecimals, int toDecimals) {
        if (fromDecimals == toDecimals) {
            return amount;
        }
        BigDecimal value = BigDecimal.valueOf(amount, fromDecimals)
                .setScale(toDecimals, java.math.RoundingMode.DOWN)
                .movePointRight(toDecimals);
        return value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : value.longValue();
    }
}
