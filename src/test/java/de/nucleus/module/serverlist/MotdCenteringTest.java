package de.nucleus.module.serverlist;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Jede mitgelieferte Meldung passt auch mit einem 16 Zeichen langen Namen in die Serverliste. */
    @Test
    @SuppressWarnings("unchecked")
    void defaultMessagesFitWithLongestName() throws IOException {
        Map<String, Object> config;
        try (InputStream in = MotdCenteringTest.class.getResourceAsStream("/modules/serverlist-motd.yml")) {
            config = new Yaml().load(in);
        }
        int maxWidth = (Integer) config.get("max-width");
        List<String> messages = (List<String>) config.get("messages");
        assertTrue(messages.size() >= 12);
        String longestName = "DerAutismusKickt";
        for (String m : messages) {
            String line = m.replace("{player}", longestName);
            assertTrue(MotdCentering.width(line) <= maxWidth,
                    "zu lang (" + MotdCentering.width(line) + " px): " + line);
        }
    }
}
