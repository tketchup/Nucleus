package de.nucleus.util;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTimeTest {

    @Test
    void parsesWordsTicksAndClock() {
        assertEquals(OptionalLong.of(1000), GameTime.parse("day"));
        assertEquals(OptionalLong.of(13000), GameTime.parse("NIGHT"));
        assertEquals(OptionalLong.of(0), GameTime.parse("6:00"), "Tick 0 = 6 Uhr");
        assertEquals(OptionalLong.of(6000), GameTime.parse("12:00"));
        assertEquals(OptionalLong.of(18000), GameTime.parse("0:00"));
        assertEquals(OptionalLong.of(500), GameTime.parse("06:30"));
        assertEquals(OptionalLong.of(23999), GameTime.parse("23999"));
    }

    @Test
    void rejectsInvalidTimes() {
        assertTrue(GameTime.parse("24000").isEmpty());
        assertTrue(GameTime.parse("25:00").isEmpty());
        assertTrue(GameTime.parse("12:60").isEmpty());
        assertTrue(GameTime.parse("mittag").isEmpty());
        assertTrue(GameTime.parse("-5").isEmpty());
    }

    @Test
    void formatsClock() {
        assertEquals("07:00", GameTime.clock(1000));
        assertEquals("00:00", GameTime.clock(18000));
        assertEquals("06:30", GameTime.clock(500));
        assertEquals("06:00", GameTime.clock(24000), "Tageswechsel");
    }
}
