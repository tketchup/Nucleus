package de.nucleus.core;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft die Sprachdateien gegeneinander und gegen den Code. */
class LanguageFilesTest {

    /** Schlüssel, die nur in einer Sprache nötig sind. */
    private static final Set<String> LANGUAGE_SPECIFIC_SECTIONS = Set.of("usage-words");

    /** Nachrichtenschlüssel, die im Code als fester Text stehen: send(x, "a.b"), get("a.b"), raw("a.b"), CommandFail("a.b"). */
    private static final Pattern KEY_IN_CODE = Pattern.compile(
            "(?:send\\([^,()]+, |get\\(|raw\\(|rawList\\(|CommandFail\\()\"([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"");

    private static Map<String, Object> load(String lang) throws IOException {
        try (InputStream in = LanguageFilesTest.class.getResourceAsStream("/lang/" + lang + ".yml")) {
            return new Yaml().load(in);
        }
    }

    /** Alle Blatt-Schlüssel als "a.b.c". Nicht-Text-Schlüssel (z. B. yes → true) fallen hier auf. */
    @SuppressWarnings("unchecked")
    private static Set<String> keys(Map<?, ?> map, String prefix, Set<String> out) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            assertTrue(e.getKey() instanceof String,
                    "Schlüssel '" + e.getKey() + "' unter '" + prefix + "' wird von YAML nicht als Text gelesen (z. B. yes/no/on/off)");
            String key = prefix.isEmpty() ? (String) e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> child) {
                keys(child, key, out);
            } else {
                out.add(key);
            }
        }
        return out;
    }

    private static Set<String> comparableKeys(String lang) throws IOException {
        Set<String> keys = keys(load(lang), "", new TreeSet<>());
        keys.removeIf(k -> LANGUAGE_SPECIFIC_SECTIONS.stream().anyMatch(s -> k.startsWith(s + ".")));
        return keys;
    }

    @Test
    void germanAndEnglishHaveTheSameKeys() throws IOException {
        Set<String> de = comparableKeys("de");
        Set<String> en = comparableKeys("en");
        Set<String> onlyDe = new TreeSet<>(de);
        onlyDe.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(de);
        assertTrue(onlyDe.isEmpty(), "Fehlt in en.yml: " + onlyDe);
        assertTrue(onlyEn.isEmpty(), "Fehlt in de.yml: " + onlyEn);
        assertEquals(load("de").get("version"), load("en").get("version"), "gleiche Dateiversion");
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        Set<String> available = comparableKeys("de");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_IN_CODE.matcher(Files.readString(file));
                while (m.find()) {
                    String key = m.group(1);
                    // Präfixe für zusammengesetzte Schlüssel ("gameplay.mode." + modus, "economy.admin-" + aktion)
                    // zählen als vorhanden
                    String prefix = key.endsWith("-") ? key : key + ".";
                    boolean found = available.contains(key) || available.stream().anyMatch(k -> k.startsWith(prefix));
                    if (!found) {
                        missing.add(key + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Im Code benutzt, aber nicht in lang/de.yml: " + missing);
    }
}
