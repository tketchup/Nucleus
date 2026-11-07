package de.nucleus.module.serverlist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotdCenteringTest {

    @Test
    void charWidths() {
        assertEquals(2, MotdCentering.charWidth('i'));
        assertEquals(3, MotdCentering.charWidth('l'));
        assertEquals(4, MotdCentering.charWidth(' '));
        assertEquals(4, MotdCentering.charWidth('['));
        assertEquals(5, MotdCentering.charWidth('f'));
        assertEquals(7, MotdCentering.charWidth('@'));
        assertEquals(6, MotdCentering.charWidth('a'));
        assertEquals(6, MotdCentering.charWidth('»'), "unbekannte Zeichen zählen 6");
    }

    @Test
    void widthAndSpaces() {
        // "ketchby.me" = k5 e6 t4 c6 h6 b6 y6 .2 m6 e6 = 53
        assertEquals(53, MotdCentering.width("ketchby.me"));
        // (250 - 53) / 2 / 4 = 24
        assertEquals(24, MotdCentering.spaces("ketchby.me", 250));
        assertEquals(0, MotdCentering.spaces("x".repeat(100), 250), "zu lange Zeile: keine Leerzeichen");
        assertEquals(0, MotdCentering.width(""));
    }
}
