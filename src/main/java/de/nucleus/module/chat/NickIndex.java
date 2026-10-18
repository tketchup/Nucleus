package de.nucleus.module.chat;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.YamlFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Verzeichnis aller vergebenen Nicknames (nicks.yml: UUID → Nick ohne Formatierung, kleingeschrieben).
 * Nötig für die Eindeutigkeit auch gegenüber Offline-Spielern und für /realname. Nur Hauptthread.
 */
final class NickIndex {

    private final NucleusPlugin plugin;
    private final YamlFile file;
    private final Map<UUID, String> byPlayer = new HashMap<>();

    NickIndex(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve("nicks.yml"));
    }

    void load() {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "nicks.yml konnte nicht gelesen werden", error);
                return;
            }
            byPlayer.clear();
            ConfigurationSection sec = yaml.getConfigurationSection("nicks");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        byPlayer.put(UUID.fromString(key), sec.getString(key, "").toLowerCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        // kaputter Eintrag
                    }
                }
            }
        }));
    }

    /** @return der Besitzer dieses Nicks (ohne Formatierung), oder null */
    @Nullable UUID owner(String plainNick) {
        String lower = plainNick.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : byPlayer.entrySet()) {
            if (e.getValue().equals(lower)) {
                return e.getKey();
            }
        }
        return null;
    }

    void set(UUID player, @Nullable String plainNick) {
        if (plainNick == null) {
            byPlayer.remove(player);
        } else {
            byPlayer.put(player, plainNick.toLowerCase(Locale.ROOT));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection sec = yaml.createSection("nicks");
        byPlayer.forEach((id, nick) -> sec.set(id.toString(), nick));
        file.save(yaml);
    }
}
