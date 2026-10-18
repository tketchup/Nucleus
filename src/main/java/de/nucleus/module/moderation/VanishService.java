package de.nucleus.module.moderation;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unsichtbarkeit über {@link Player#hidePlayer}: Der Server schickt den Spieler dann gar nicht mehr an
 * die anderen Clients (weder Tab-Liste noch Entity), es ist also echtes Verstecken statt eines
 * Unsichtbarkeits-Tranks. Wer nucleus.vanish.see hat, sieht versteckte Spieler weiterhin.
 */
public final class VanishService {

    static final String SEE = "nucleus.vanish.see";

    private final NucleusPlugin plugin;
    private final ModerationModule module;
    /** Thread-sicher: der Server-Ping fragt asynchron. */
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    VanishService(NucleusPlugin plugin, ModerationModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    public boolean isVanished(Player player) {
        return vanished.contains(player.getUniqueId());
    }

    Set<UUID> vanishedIds() {
        return vanished;
    }

    /** Schaltet um; mit announce werden (falls eingestellt) Fake-Beitritts-/Austrittsnachrichten gesendet. */
    void set(Player player, boolean value, boolean announce) {
        if (value == isVanished(player)) {
            return;
        }
        if (value) {
            vanished.add(player.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(player) && !viewer.hasPermission(SEE)) {
                    viewer.hidePlayer(plugin, player);
                }
            }
            player.setSleepingIgnored(true);
        } else {
            vanished.remove(player.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.showPlayer(plugin, player);
            }
            player.setSleepingIgnored(false);
        }
        PlayerData data = plugin.playerData().get(player);
        data.vanished(value && module.settings().vanishPersist());
        if (announce && module.settings().vanishFakeMessages()) {
            // Vanilla-Übersetzung, damit die Fake-Nachricht genau wie die echte aussieht
            Component fake = Component.translatable(value ? "multiplayer.player.left" : "multiplayer.player.joined",
                    NamedTextColor.YELLOW, player.displayName());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.hasPermission(SEE)) {
                    viewer.sendMessage(fake);
                }
            }
        }
    }

    /** Neuer Spieler: alle Versteckten vor ihm verbergen (außer er darf sie sehen). */
    void hideVanishedFrom(Player viewer) {
        if (viewer.hasPermission(SEE)) {
            return;
        }
        for (UUID id : vanished) {
            Player hidden = Bukkit.getPlayer(id);
            if (hidden != null && !hidden.equals(viewer)) {
                viewer.hidePlayer(plugin, hidden);
            }
        }
    }

    void quit(Player player) {
        vanished.remove(player.getUniqueId());
    }

    /** Beim Aktivieren (z. B. nach /nucleus reload): gespeicherten Vanish der Online-Spieler herstellen. */
    void restoreOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.playerData().get(p).vanished() && p.hasPermission("nucleus.vanish")) {
                set(p, true, false);
            }
        }
    }

    /** Modul wird abgeschaltet: alle wieder sichtbar machen (gespeicherter Status bleibt). */
    void revealAll() {
        for (UUID id : vanished) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Bukkit.getOnlinePlayers().forEach(v -> v.showPlayer(plugin, p));
                p.setSleepingIgnored(false);
            }
        }
        vanished.clear();
    }
}
