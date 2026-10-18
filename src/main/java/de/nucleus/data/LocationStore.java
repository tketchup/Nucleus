package de.nucleus.data;

import de.nucleus.NucleusPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Benannte Orte in einer eigenen YAML-Datei (z. B. jails.yml). Im Speicher gehalten (nur Hauptthread),
 * jede Änderung wird asynchron geschrieben. Namen sind nicht groß-/kleinschreibungsabhängig.
 */
public final class LocationStore {

    private final NucleusPlugin plugin;
    private final YamlFile file;
    private final String section;
    private final Map<String, NamedLocation> entries = new TreeMap<>();

    public LocationStore(NucleusPlugin plugin, String fileName, String section) {
        this.plugin = plugin;
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve(fileName));
        this.section = section;
    }

    public void load() {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Konnte " + section + " nicht laden", error);
                return;
            }
            entries.clear();
            ConfigurationSection sec = yaml.getConfigurationSection(section);
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    NamedLocation loc = NamedLocation.read(sec.getConfigurationSection(key));
                    if (loc != null) {
                        entries.put(key.toLowerCase(Locale.ROOT), loc);
                    }
                }
            }
        }));
    }

    public Map<String, NamedLocation> all() {
        return Collections.unmodifiableMap(entries);
    }

    public @Nullable NamedLocation get(String name) {
        return entries.get(name.toLowerCase(Locale.ROOT));
    }

    /** @return true, wenn der Eintrag neu ist */
    public boolean set(String name, NamedLocation loc) {
        boolean isNew = entries.put(name.toLowerCase(Locale.ROOT), loc) == null;
        save();
        return isNew;
    }

    public boolean remove(String name) {
        boolean removed = entries.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection sec = yaml.createSection(section);
        entries.forEach((name, loc) -> loc.write(sec.createSection(name)));
        file.save(yaml);
    }
}
