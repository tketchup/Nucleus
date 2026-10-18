package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.YamlFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

/** Warps in warps.yml. Im Speicher gehalten, jede Änderung wird asynchron geschrieben. */
public final class WarpStore {

    private final NucleusPlugin plugin;
    private final YamlFile file;
    private final Map<String, NamedLocation> warps = new TreeMap<>();
    private final Map<String, String> icons = new java.util.HashMap<>();

    WarpStore(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve("warps.yml"));
    }

    void load() {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "warps.yml konnte nicht gelesen werden", error);
                return;
            }
            warps.clear();
            icons.clear();
            ConfigurationSection sec = yaml.getConfigurationSection("warps");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection w = sec.getConfigurationSection(key);
                    NamedLocation loc = NamedLocation.read(w);
                    if (loc != null) {
                        String lower = key.toLowerCase(Locale.ROOT);
                        warps.put(lower, loc);
                        if (w.isString("icon")) {
                            icons.put(lower, w.getString("icon"));
                        }
                    }
                }
            }
        }));
    }

    public Map<String, NamedLocation> all() {
        return Collections.unmodifiableMap(warps);
    }

    public @Nullable NamedLocation get(String name) {
        return warps.get(name.toLowerCase(Locale.ROOT));
    }

    /** @return true, wenn der Warp neu ist (false = überschrieben) */
    public boolean set(String name, NamedLocation loc) {
        boolean isNew = warps.put(name.toLowerCase(Locale.ROOT), loc) == null;
        save();
        return isNew;
    }

    public boolean remove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean removed = warps.remove(key) != null;
        if (removed) {
            icons.remove(key);
            save();
        }
        return removed;
    }

    /** @return false, wenn der alte Warp fehlt oder der neue Name belegt ist */
    public boolean rename(String oldName, String newName) {
        String from = oldName.toLowerCase(Locale.ROOT);
        String to = newName.toLowerCase(Locale.ROOT);
        if (!warps.containsKey(from) || (warps.containsKey(to) && !from.equals(to))) {
            return false;
        }
        warps.put(to, warps.remove(from));
        String icon = icons.remove(from);
        if (icon != null) {
            icons.put(to, icon);
        }
        save();
        return true;
    }

    public @Nullable String icon(String name) {
        return icons.get(name.toLowerCase(Locale.ROOT));
    }

    public void icon(String name, String itemKey) {
        icons.put(name.toLowerCase(Locale.ROOT), itemKey);
        save();
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection sec = yaml.createSection("warps");
        warps.forEach((name, loc) -> {
            ConfigurationSection w = sec.createSection(name);
            loc.write(w);
            if (icons.containsKey(name)) {
                w.set("icon", icons.get(name));
            }
        });
        file.save(yaml);
    }
}
