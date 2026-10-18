package de.nucleus.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {

    @Test
    void formatsGermanDecimals() {
        assertEquals("1.234,50", Formats.decimal(new BigDecimal("1234.5"), 2));
        assertEquals("0,00", Formats.decimal(BigDecimal.ZERO, 2));
        assertEquals("1.000.000", Formats.decimal(new BigDecimal("1000000"), 0));
        // kaufmännisch runden
        assertEquals("0,13", Formats.decimal(new BigDecimal("0.125"), 2));
    }

    @Test
    void formatsIntegersAndCoords() {
        assertEquals("12.345", Formats.integer(12345));
        assertEquals("-3,5", Formats.coord(-3.5));
    }
}
