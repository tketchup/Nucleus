package de.nucleus.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeParserTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Duration.ofSeconds(30), TimeParser.parse("30s").orElseThrow());
        assertEquals(Duration.ofMinutes(5), TimeParser.parse("5m").orElseThrow());
        assertEquals(Duration.ofHours(2), TimeParser.parse("2h").orElseThrow());
        assertEquals(Duration.ofDays(1), TimeParser.parse("1d").orElseThrow());
        assertEquals(Duration.ofDays(14), TimeParser.parse("2w").orElseThrow());
        assertEquals(Duration.ofDays(30), TimeParser.parse("1mo").orElseThrow());
        assertEquals(Duration.ofDays(365), TimeParser.parse("1y").orElseThrow());
    }

    @Test
    void parsesCombinations() {
        assertEquals(Duration.ofDays(1).plusHours(2).plusMinutes(30), TimeParser.parse("1d2h30m").orElseThrow());
        assertEquals(Duration.ofMinutes(90), TimeParser.parse("1h30m").orElseThrow());
        // Monat vor Minute: "mo" darf nicht als "m" + "o" gelesen werden
        assertEquals(Duration.ofDays(30).plusMinutes(1), TimeParser.parse("1mo1m").orElseThrow());
    }

    @Test
    void ignoresCaseAndSurroundingSpaces() {
        assertEquals(Duration.ofHours(3), TimeParser.parse(" 3H ").orElseThrow());
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(TimeParser.parse("").isEmpty());
        assertTrue(TimeParser.parse(null).isEmpty());
        assertTrue(TimeParser.parse("abc").isEmpty());
        assertTrue(TimeParser.parse("10").isEmpty(), "Zahl ohne Einheit");
        assertTrue(TimeParser.parse("h").isEmpty(), "Einheit ohne Zahl");
        assertTrue(TimeParser.parse("5x").isEmpty(), "unbekannte Einheit");
        assertTrue(TimeParser.parse("0s").isEmpty(), "Dauer 0");
        assertTrue(TimeParser.parse("-5m").isEmpty());
        assertTrue(TimeParser.parse("99999999999999999999s").isEmpty(), "Überlauf");
    }

    @Test
    void formatsGerman() {
        assertEquals("2 Stunden, 30 Minuten", TimeParser.format(Duration.ofMinutes(150)));
        assertEquals("1 Tag, 1 Sekunde", TimeParser.format(Duration.ofSeconds(86_401)));
        assertEquals("0 Sekunden", TimeParser.format(Duration.ZERO));
        // angefangene Sekunde wird aufgerundet
        assertEquals("1 Sekunde", TimeParser.format(Duration.ofMillis(300)));
    }

    @Test
    void compactRoundTrips() {
        assertEquals("1d2h", TimeParser.compact(Duration.ofHours(26)));
        assertEquals("1h30m5s", TimeParser.compact(Duration.ofSeconds(5405)));
        assertEquals("0s", TimeParser.compact(Duration.ZERO));
        // Kurzform lässt sich wieder einlesen
        assertEquals(Duration.ofSeconds(5405), TimeParser.parse(TimeParser.compact(Duration.ofSeconds(5405))).orElseThrow());
    }

    @Test
    void formatsEnglish() {
        assertEquals("1 day, 2 hours", TimeParser.format(Duration.ofHours(26), TimeParser.Units.ENGLISH));
        assertEquals("1 second", TimeParser.format(Duration.ofMillis(10), TimeParser.Units.ENGLISH));
    }
}
