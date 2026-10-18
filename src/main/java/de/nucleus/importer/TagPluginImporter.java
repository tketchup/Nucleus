package de.nucleus.importer;

import de.nucleus.NucleusPlugin;
import de.nucleus.module.chat.ChatModule;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Übernimmt Daten aus dem TagPlugin (TagMC): Tag-Definitionen, vergebene Tags und die Formate für
 * Chat und Tab-Liste. Vorhandene Nucleus-Tags und bereits vergebene Tags bleiben unverändert; die
 * Formate werden übernommen (die bisherigen stehen danach im Log).
 */
public final class TagPluginImporter {

    private final NucleusPlugin plugin;

    public TagPluginImporter(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    public File defaultFolder() {
        return new File(plugin.getDataFolder().getParentFile(), "TagPlugin");
    }

    public void run(CommandSender sender, File folder) {
        ChatModule chat = plugin.modules().get("chat", ChatModule.class);
        if (chat == null) {
            plugin.messages().send(sender, "core.import-tags-chat-off");
            return;
        }
        if (!new File(folder, "tags.yml").isFile()) {
            plugin.messages().send(sender, "core.import-no-folder", "folder", folder.getPath());
            return;
        }
        plugin.messages().send(sender, "core.import-started", "folder", folder.getPath());
        // Dateien im IO-Thread lesen (YamlConfiguration ist dafür in Ordnung, nur Datenverarbeitung)
        plugin.io().supply(() -> new YamlConfiguration[]{
                YamlConfiguration.loadConfiguration(new File(folder, "tags.yml")),
                YamlConfiguration.loadConfiguration(new File(folder, "players.yml")),
                YamlConfiguration.loadConfiguration(new File(folder, "config.yml"))
        }).whenComplete((files, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.messages().send(sender, "error.internal");
                return;
            }
            apply(sender, chat, files[0], files[1], files[2]);
        }));
    }

    private void apply(CommandSender sender, ChatModule chat, YamlConfiguration tagsYml,
                       YamlConfiguration playersYml, YamlConfiguration configYml) {
        int tags = 0;
        int skipped = 0;
        ConfigurationSection tagSec = tagsYml.getConfigurationSection("tags");
        if (tagSec != null) {
            for (String id : tagSec.getKeys(false)) {
                String lower = id.toLowerCase(java.util.Locale.ROOT);
                if (chat.tags().get(lower) != null) {
                    skipped++;
                } else if (chat.importTag(lower, tagSec.getConfigurationSection(id))) {
                    tags++;
                }
            }
        }

        // Formate übernehmen: TagMC <name> = reiner Name (<player>), <displayname> = <display_name>
        var cfg = plugin.config("chat");
        List<String> old = new ArrayList<>();
        for (Map.Entry<String, String> e : Map.of(
                "chat.format", "format", "chat.format-no-tag", "format-no-tag",
                "tablist.format", "tablist.format", "tablist.format-no-tag", "tablist.format-no-tag").entrySet()) {
            String value = configYml.getString(e.getKey());
            if (value != null && !value.isBlank()) {
                old.add(e.getValue() + "=" + cfg.getString(e.getValue()));
                cfg.set(e.getValue(), value.replace("<name>", "<player>").replace("<displayname>", "<display_name>"));
            }
        }
        if (configYml.isBoolean("tablist.enabled")) {
            cfg.set("tablist.enabled", configYml.getBoolean("tablist.enabled"));
        }
        if (configYml.isBoolean("chat.color-codes.require-permission")) {
            cfg.set("colors-for-everyone", !configYml.getBoolean("chat.color-codes.require-permission"));
        }
        if (configYml.isBoolean("self-select.enabled")) {
            cfg.set("tags.self-select", configYml.getBoolean("self-select.enabled"));
        }
        if (!old.isEmpty()) {
            plugin.getLogger().info("Chat-Formate vor dem TagPlugin-Import: " + old);
        }
        plugin.moduleConfigs().save("chat");

        // Vergebene Tags: nur, wenn der Spieler in Nucleus noch keinen hat
        AtomicInteger assigned = new AtomicInteger();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        ConfigurationSection players = playersYml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                String tagId = players.getString(key + ".tag");
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (tagId == null || chat.tags().get(tagId) == null) {
                    continue;
                }
                pending.add(plugin.playerData().withData(uuid, data -> {
                    if (data.tag() == null) {
                        data.tag(tagId.toLowerCase(java.util.Locale.ROOT));
                        assigned.incrementAndGet();
                    }
                }));
            }
        }
        final int tagCount = tags;
        final int skippedCount = skipped;
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ok, err) -> plugin.sync(() -> {
            plugin.modules().reloadSettings("chat"); // neue Formate gelten sofort, Tab-Liste wird aufgefrischt
            plugin.messages().send(sender, "core.import-tags-done", "tags", tagCount, "skipped", skippedCount,
                    "players", assigned.get());
        }));
    }
}
