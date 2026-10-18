package de.nucleus.module.moderation;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jspecify.annotations.Nullable;

/**
 * Jail: Spieler sitzen an einem Jail-Punkt fest (Radius in der Config), bis die Zeit abläuft oder
 * /unjail. Die Zeit läuft in Echtzeit, auch wenn der Spieler offline ist. Beim Freilassen geht es
 * zurück zur Position vor dem Jail (oder zum Weltspawn, wenn sie unbekannt ist).
 */
final class JailService {

    private final NucleusPlugin plugin;
    private final ModerationModule module;

    JailService(NucleusPlugin plugin, ModerationModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** Ort des Jails, in dem der Spieler sitzt, oder null (nicht im Jail / Jail gelöscht / Welt fehlt). */
    @Nullable Location jailLocation(PlayerData data) {
        if (data.jail() == null) {
            return null;
        }
        NamedLocation loc = module.jails().get(data.jail());
        return loc == null ? null : loc.toLocation();
    }

    boolean isJailed(Player player) {
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        return data != null && data.jail() != null;
    }

    /**
     * Teleport ohne Wartezeit und mit Ursache UNKNOWN, damit /back den Jail-Teleport nicht als
     * Rückkehrpunkt speichert.
     */
    void sendToJail(Player player, PlayerData data) {
        Location loc = jailLocation(data);
        if (loc != null) {
            player.teleportAsync(loc, TeleportCause.UNKNOWN);
        }
    }

    void release(Player player, PlayerData data, boolean expired) {
        NamedLocation back = data.jailReturn();
        data.jail(null, 0, null);
        Location target = back != null ? back.toLocation() : null;
        if (target == null) {
            target = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        player.teleportAsync(target, TeleportCause.UNKNOWN);
        plugin.messages().send(player, expired ? "moderation.jail-expired" : "moderation.unjailed-target");
    }

    /** Jede Sekunde: abgelaufene Jail-Zeiten beenden. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.playerData().getIfLoaded(p.getUniqueId());
            if (data != null && data.jail() != null && data.jailUntil() <= now) {
                release(p, data, true);
            }
        }
    }
}
