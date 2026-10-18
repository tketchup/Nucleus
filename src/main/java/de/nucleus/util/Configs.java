package de.nucleus.util;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Hilfen für config.yml. Grundregel im ganzen Projekt: Getter OHNE Fallback-Parameter benutzen
 * (getInt(path) statt getInt(path, 3)). Nur diese fragen die Standardwerte der mitgelieferten
 * config.yml ab; so ist die Jar-Config die einzige Quelle für Standardwerte, auch wenn die Datei auf
 * dem Server von einer älteren Version stammt.
 */
public final class Configs {

    private Configs() {
    }

    /** String ohne null: fehlt der Schlüssel auch in den Defaults, ein leerer Text. */
    public static String string(ConfigurationSection c, String path) {
        String value = c.getString(path);
        return value != null ? value : "";
    }
}
