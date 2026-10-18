package de.nucleus.module.info;

import de.nucleus.NucleusPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK-Status: manuell per /afk oder automatisch nach Inaktivität, optional Kick, Markierung in der
 * Tab-Liste und Ausnahme beim Schlafen. Nur Hauptthread (außer {@link #isAfk}).
 */
public final class AfkService {

    private final NucleusPlugin plugin;
    private final InfoModule module;
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    /** AFK-Spieler → Grund (leer = ohne Grund). ConcurrentHashMap: isAfk wird evtl. asynchron gefragt. */
    private final Map<UUID, String> afk = new ConcurrentHashMap<>();
    /** Ursprünglicher Tab-Listen-Name, um ihn nach dem AFK wiederherzustellen. */
    private final Map<UUID, Component> savedListNames = new HashMap<>();

    AfkService(NucleusPlugin plugin, InfoModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    public boolean isAfk(Player player) {
        return afk.containsKey(player.getUniqueId());
    }

    public @Nullable String reason(Player player) {
        return afk.get(player.getUniqueId());
    }

    /** Spieler war aktiv (Bewegung, Chat, Befehl, Interaktion): Zeit merken, AFK beenden. */
    void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player)) {
            setAfk(player, false, null);
        }
    }

    void setAfk(Player player, boolean value, @Nullable String reason) {
        UUID id = player.getUniqueId();
        if (value == isAfk(player)) {
            return;
        }
        InfoModule.Settings s = module.settings();
        if (value) {
            afk.put(id, reason == null ? "" : reason);
        } else {
            afk.remove(id);
            lastActivity.put(id, System.currentTimeMillis());
        }

        // Gestaltet das chat-Modul die Tab-Liste, baut es den Namen samt AFK-Marker selbst auf.
        // Sonst nur mit tablist-marker anfassen (die Tab-Liste kann einem anderen Plugin gehören).
        var chat = plugin.modules().get("chat", de.nucleus.module.chat.ChatModule.class);
        if (chat != null && chat.tablistEnabled()) {
            chat.refreshTab(player);
        } else if (s.tablistMarker()) {
            if (value) {
                savedListNames.put(id, player.playerListName());
                player.playerListName(player.playerListName().append(plugin.messages().get("afk.tablist-suffix")));
            } else {
                player.playerListName(savedListNames.remove(id));
            }
        }
        if (s.ignoreSleep()) {
            player.setSleepingIgnored(value);
        }
        if (s.broadcastAfk()) {
            if (value && reason != null && !reason.isBlank()) {
                broadcast("afk.now-afk-reason", "player", player.displayName(), "reason", reason);
            } else {
                broadcast(value ? "afk.now-afk" : "afk.no-longer-afk", "player", player.displayName());
            }
        } else {
            plugin.messages().send(player, value ? "afk.self-afk" : "afk.self-back");
        }
    }

    private void broadcast(String key, Object... placeholders) {
        Component msg = plugin.messages().get(key, placeholders);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    /** Jede Sekunde: automatisch AFK setzen und ggf. kicken. */
    void tick() {
        InfoModule.Settings s = module.settings();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            long idle = now - lastActivity.computeIfAbsent(p.getUniqueId(), k -> now);
            if (!isAfk(p) && s.autoAfkMillis() > 0 && idle >= s.autoAfkMillis() && !p.hasPermission("nucleus.afk.exempt")) {
                setAfk(p, true, null);
            }
            if (isAfk(p) && s.kickMillis() > 0 && idle >= s.kickMillis() && !p.hasPermission("nucleus.afk.kickexempt")) {
                p.kick(plugin.messages().get("afk.kick"));
            }
        }
    }

    void quit(Player player) {
        UUID id = player.getUniqueId();
        lastActivity.remove(id);
        afk.remove(id);
        savedListNames.remove(id);
    }

    /** Modul wird abgeschaltet: alle stillschweigend zurücksetzen. */
    void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (afk.remove(id) != null) {
                if (savedListNames.containsKey(id)) {
                    p.playerListName(savedListNames.remove(id));
                }
                if (module.settings().ignoreSleep()) {
                    p.setSleepingIgnored(false);
                }
            }
        }
        afk.clear();
        lastActivity.clear();
        savedListNames.clear();
        var chat = plugin.modules().get("chat", de.nucleus.module.chat.ChatModule.class);
        if (chat != null && chat.tablistEnabled()) {
            Bukkit.getOnlinePlayers().forEach(chat::refreshTab);
        }
    }
}
