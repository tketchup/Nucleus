package de.nucleus.util;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Ergänzt in einer bestehenden Datei (config.yml, messages.yml) alle Einträge, die in der
 * mitgelieferten Version neu dazugekommen sind, samt Kommentaren. Vorhandene Werte des Nutzers
 * bleiben unverändert, auch wenn sie vom Standard abweichen.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /** @return Anzahl der ergänzten Einträge (0 = nichts zu speichern) */
    public static int addMissing(Configuration target, ConfigurationSection defaults) {
        int added = 0;
        // getKeys(true) liefert Eltern vor Kindern, Abschnitte entstehen also vor ihren Einträgen
        for (String path : defaults.getKeys(true)) {
            if (target.contains(path, true)) {
                continue; // true = Defaults ignorieren, nur die echte Datei zählt
            }
            if (defaults.isConfigurationSection(path)) {
                target.createSection(path);
            } else {
                target.set(path, defaults.get(path));
                added++;
            }
            target.setComments(path, defaults.getComments(path));
            target.setInlineComments(path, defaults.getInlineComments(path));
        }
        if (added > 0 && target instanceof YamlConfiguration yaml) {
            // Lange MiniMessage-Texte nicht nach 80 Zeichen umbrechen
            yaml.options().width(1000);
        }
        return added;
    }
}
