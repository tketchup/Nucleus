package de.nucleus.util;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void parsesWholeAndDecimalAmounts() {
        assertEquals(OptionalLong.of(1000), Money.parse("10", 2));
        assertEquals(OptionalLong.of(1050), Money.parse("10.5", 2));
        assertEquals(OptionalLong.of(1055), Money.parse("10,55", 2), "Komma als Dezimaltrenner");
        assertEquals(OptionalLong.of(1), Money.parse("0.01", 2));
        assertEquals(OptionalLong.of(0), Money.parse("0", 2));
        assertEquals(OptionalLong.of(1000), Money.parse("10.000", 2), "Nullen hinten sind erlaubt");
    }

    @Test
    void parsesSuffixes() {
        assertEquals(OptionalLong.of(100_000), Money.parse("1k", 2));
        assertEquals(OptionalLong.of(250_000_000), Money.parse("2,5m", 2));
        assertEquals(OptionalLong.of(1500), Money.parse("1.5K", 0));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertTrue(Money.parse("10.555", 2).isEmpty(), "zu viele Nachkommastellen");
        assertTrue(Money.parse("1.5", 0).isEmpty(), "Nachkommastellen bei decimals 0");
        assertTrue(Money.parse("-5", 2).isEmpty(), "negativ");
        assertTrue(Money.parse("abc", 2).isEmpty());
        assertTrue(Money.parse("", 2).isEmpty());
        assertTrue(Money.parse("1.000,50", 2).isEmpty(), "keine Tausendertrennzeichen");
        assertTrue(Money.parse("1e5", 2).isEmpty(), "keine Exponentenschreibweise");
        assertTrue(Money.parse("NaN", 2).isEmpty());
        assertTrue(Money.parse("99999999999999999999", 2).isEmpty(), "Überlauf");
        assertTrue(Money.parse(null, 2).isEmpty());
    }

    @Test
    void noFloatingPointErrors() {
        // 0.1 + 0.2 als Cent: exakt 30, nicht 30.000000000000004
        long sum = Money.parse("0.1", 2).orElseThrow() + Money.parse("0.2", 2).orElseThrow();
        assertEquals(30, sum);
        assertEquals("0,30", Money.format(sum, 2));
    }

    @Test
    void formatsGerman() {
        assertEquals("1.234,56", Money.format(123456, 2));
        assertEquals("0,05", Money.format(5, 2));
        assertEquals("1.234", Money.format(1234, 0));
        assertEquals("92.233.720.368.547.758,07", Money.format(Long.MAX_VALUE, 2));
    }

    @Test
    void rescalesBetweenDecimalSettings() {
        assertEquals(12345, Money.rescale(12345, 2, 2));
        assertEquals(123, Money.rescale(12345, 2, 0), "abrunden");
        assertEquals(1234500, Money.rescale(12345, 2, 4));
        assertEquals(Long.MAX_VALUE, Money.rescale(Long.MAX_VALUE, 0, 2), "Überlauf wird gedeckelt");
        assertEquals(100, Money.one(2));
        assertEquals(1, Money.one(0));
    }

    @Test
    void convertsFromBigDecimalWithoutFavouringTheAccount() {
        java.math.BigDecimal price = new java.math.BigDecimal("1.005");
        assertEquals(OptionalLong.of(100), Money.fromDecimal(price, 2, java.math.RoundingMode.DOWN), "Einzahlung abrunden");
        assertEquals(OptionalLong.of(101), Money.fromDecimal(price, 2, java.math.RoundingMode.UP), "Abbuchung aufrunden");
        assertEquals(OptionalLong.of(1234), Money.fromDecimal(new java.math.BigDecimal("12.34"), 2, java.math.RoundingMode.DOWN));
        assertTrue(Money.fromDecimal(new java.math.BigDecimal("-1"), 2, java.math.RoundingMode.DOWN).isEmpty());
        assertTrue(Money.fromDecimal(new java.math.BigDecimal("1e30"), 2, java.math.RoundingMode.DOWN).isEmpty(), "Überlauf");
        assertEquals(new java.math.BigDecimal("1234.56"), Money.toDecimal(123456, 2));
    }
}
