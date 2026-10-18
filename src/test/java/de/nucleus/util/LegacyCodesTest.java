package de.nucleus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyCodesTest {

    @Test
    void convertsColorAndFormatCodes() {
        assertEquals("<red>Hallo <bold>Welt", LegacyCodes.toMiniMessage("&cHallo &lWelt"));
        assertEquals("<green>A<reset>B", LegacyCodes.toMiniMessage("&AA&rB"));
    }

    @Test
    void convertsHexCodes() {
        assertEquals("<#ff8800>orange", LegacyCodes.toMiniMessage("&#ff8800orange"));
    }

    @Test
    void leavesEverythingElseAlone() {
        assertEquals("Tom & Jerry", LegacyCodes.toMiniMessage("Tom & Jerry"));
        assertEquals("&x &#12 &", LegacyCodes.toMiniMessage("&x &#12 &"));
        assertEquals("<yellow>schon MiniMessage", LegacyCodes.toMiniMessage("<yellow>schon MiniMessage"));
    }
}
