package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.YamlFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Spawnpunkte in spawns.yml: ein globaler Spawn ("global") und optional einer pro Welt.
 * ConcurrentHashMap, weil der Spawn für neue Spieler im asynchronen Spawn-Event gelesen wird.
 */
public final class SpawnStore {

    private static final String GLOBAL = "global";

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    private final YamlFile file;
    private final Map<String, NamedLocation> spawns = new ConcurrentHashMap<>();

    SpawnStore(NucleusPlugin plugin, TeleportModule module) {
        this.plugin = plugin;
        this.module = module;
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve("spawns.yml"));
    }

    void load() {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "spawns.yml konnte nicht gelesen werden", error);
                return;
            }
            spawns.clear();
            ConfigurationSection sec = yaml.getConfigurationSection("spawns");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    NamedLocation loc = NamedLocation.read(sec.getConfigurationSection(key));
                    if (loc != null) {
                        spawns.put(key, loc);
                    }
                }
            }
        }));
    }

    /** /setspawn: im Welt-Modus für die aktuelle Welt, sonst global. */
    public void set(Location loc) {
        String key = module.settings().spawnPerWorld() ? loc.getWorld().getName() : GLOBAL;
        spawns.put(key, NamedLocation.of(loc));
        // Erster Spawn überhaupt: auch als globalen Spawn nehmen (für neue Spieler)
        spawns.putIfAbsent(GLOBAL, NamedLocation.of(loc));
        save();
    }

    /** Noch gar kein Spawn gesetzt (für den Import: vorhandene Spawns nie überschreiben). */
    public boolean isEmpty() {
        return spawns.isEmpty();
    }

    /** Spawn für eine Welt: Welt-Spawn (falls aktiv) → globaler Spawn → Vanilla-Spawn. */
    public Location spawnFor(World world) {
        if (module.settings().spawnPerWorld()) {
            Location own = resolve(world.getName());
            if (own != null) {
                return own;
            }
        }
        Location global = resolve(GLOBAL);
        return global != null ? global : world.getSpawnLocation();
    }

    /** Spawn für neue Spieler. */
    public Location mainSpawn() {
        Location global = resolve(GLOBAL);
        return global != null ? global : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private @Nullable Location resolve(String key) {
        NamedLocation loc = spawns.get(key);
        return loc == null ? null : loc.toLocation();
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection sec = yaml.createSection("spawns");
        spawns.forEach((name, loc) -> loc.write(sec.createSection(name)));
        file.save(yaml);
    }
}
