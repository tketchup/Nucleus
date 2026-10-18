package de.nucleus.core;

import de.nucleus.NucleusPlugin;
import de.nucleus.util.ConfigUpdater;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Einstellungen pro Modul in {@code plugins/Nucleus/modules/<modul>.yml}. Jede Datei bekommt die
 * mitgelieferte Version als Standard; neue Einträge werden mit Kommentaren ergänzt.
 *
 * <p>Die Dateien sind klein und werden nur beim Start und bei /nucleus reload gelesen (wie config.yml);
 * geschrieben wird immer asynchron.
 */
public final class ModuleConfigs {

    /**
     * Frühere Versionen hatten alles in config.yml. Alter Abschnitt → (Moduldatei, neuer Pfad-Präfix).
     * Leerer Präfix = Abschnitt wandert auf die oberste Ebene der Moduldatei.
     */
    private static final Map<String, String[]> MIGRATION = new LinkedHashMap<>();

    static {
        MIGRATION.put("teleport", new String[]{"teleport", ""});
        for (String s : new String[]{"gui", "homes", "warps", "spawn", "tpa", "back"}) {
            MIGRATION.put(s, new String[]{"teleport", s});
        }
        MIGRATION.put("chat", new String[]{"chat", ""});
        for (String s : new String[]{"motd", "rules", "afk", "near"}) {
            MIGRATION.put(s, new String[]{"info", s});
        }
        MIGRATION.put("economy", new String[]{"economy", ""});
        MIGRATION.put("moderation", new String[]{"moderation", ""});
        MIGRATION.put("gameplay", new String[]{"gameplay", ""});
    }

    private final NucleusPlugin plugin;
    private final Map<String, YamlConfiguration> configs = new HashMap<>();

    public ModuleConfigs(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    /** Einstellungen eines Moduls (beim ersten Zugriff geladen). */
    public FileConfiguration get(String module) {
        return configs.computeIfAbsent(module, this::load);
    }

    /** /nucleus reload: alle Dateien neu lesen. */
    public void reloadAll() {
        configs.clear();
    }

    /** Asynchron speichern, z. B. nach Änderungen im Spiel. */
    public void save(String module) {
        YamlConfiguration yaml = configs.get(module);
        if (yaml != null) {
            yaml.options().width(1000);
            plugin.io().write(file(module).toPath(), yaml.saveToString());
        }
    }

    private File file(String module) {
        return new File(plugin.getDataFolder(), "modules/" + module + ".yml");
    }

    private YamlConfiguration load(String module) {
        String resource = "modules/" + module + ".yml";
        File file = file(module);
        if (!file.exists() && plugin.getResource(resource) != null) {
            plugin.saveResource(resource, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                int added = ConfigUpdater.addMissing(yaml, defaults);
                if (added > 0) {
                    yaml.options().width(1000);
                    plugin.io().write(file.toPath(), yaml.saveToString());
                    plugin.getLogger().info(resource + " um " + added + " neue Einträge ergänzt.");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Standardwerte für " + resource + " nicht lesbar: " + e.getMessage());
        }
        return yaml;
    }

    /**
     * Einmalig beim Start: Abschnitte aus einer alten, großen config.yml in die Moduldateien
     * übernehmen (Werte des Nutzers bleiben erhalten) und aus config.yml entfernen. Die alte Datei
     * wird vorher als config.yml.pre-split.bak gesichert. Läuft synchron, weil danach direkt gelesen wird.
     */
    public void migrateFromMainConfig() {
        FileConfiguration main = plugin.getConfig();
        boolean needed = MIGRATION.keySet().stream().anyMatch(s -> main.contains(s, true));
        if (!needed) {
            return;
        }
        File mainFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            Files.copy(mainFile.toPath(), new File(plugin.getDataFolder(), "config.yml.pre-split.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Backup der config.yml fehlgeschlagen, Aufteilung abgebrochen", e);
            return;
        }
        for (Map.Entry<String, String[]> e : MIGRATION.entrySet()) {
            String oldSection = e.getKey();
            if (!main.contains(oldSection, true)) {
                continue;
            }
            String module = e.getValue()[0];
            String prefix = e.getValue()[1];
            FileConfiguration target = get(module);
            ConfigurationSection sec = main.getConfigurationSection(oldSection);
            if (sec != null) {
                for (String key : sec.getKeys(true)) {
                    if (!sec.isConfigurationSection(key)) {
                        target.set(prefix.isEmpty() ? key : prefix + "." + key, sec.get(key));
                    }
                }
            } else {
                target.set(prefix.isEmpty() ? oldSection : prefix, main.get(oldSection));
            }
            main.set(oldSection, null);
        }
        for (String module : configs.keySet()) {
            save(module);
        }
        plugin.saveConfigAsync();
        plugin.getLogger().info("Einstellungen auf modules/*.yml aufgeteilt (Backup: config.yml.pre-split.bak).");
    }
}
