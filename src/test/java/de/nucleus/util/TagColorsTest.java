package de.nucleus.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagColorsTest {

    @Test
    void acceptsAllColorKinds() {
        assertEquals(Optional.of("gold"), TagColors.normalize("Gold"));
        assertEquals(Optional.of("#FF55AA"), TagColors.normalize("#ff55aa"));
        assertEquals(Optional.of("gradient:#55FF55:#00AAAA"), TagColors.normalize("gradient:#55ff55:#00aaaa"));
        assertEquals(Optional.of("gradient:dark_red:red"), TagColors.normalize("<gradient:dark_red:red>"));
        assertEquals(Optional.of("gradient:red:#00FF00:blue"), TagColors.normalize("gradient:red:#00ff00:blue"), "mehrere Stopps");
        assertEquals(Optional.of("rainbow"), TagColors.normalize("rainbow"));
        assertEquals("<gold>", TagColors.openTag("gold"));
    }

    @Test
    void rejectsInvalidColors() {
        assertTrue(TagColors.normalize("lila").isEmpty());
        assertTrue(TagColors.normalize("#fff").isEmpty(), "zu kurzer Hex");
        assertTrue(TagColors.normalize("gradient:red").isEmpty(), "Verlauf braucht zwei Farben");
        assertTrue(TagColors.normalize("gradient:red:lila").isEmpty());
        assertTrue(TagColors.normalize("").isEmpty());
        assertTrue(TagColors.normalize(null).isEmpty());
    }
}
