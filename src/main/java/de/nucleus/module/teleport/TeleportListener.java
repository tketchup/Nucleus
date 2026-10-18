package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.PlayerData;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Spawn für neue Spieler, Respawn am Spawn, /back-Positionen und Aufräumen beim Verlassen. */
final class TeleportListener implements Listener {

    private final NucleusPlugin plugin;
    private final TeleportModule module;

    TeleportListener(NucleusPlugin plugin, TeleportModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /**
     * Neue Spieler direkt am Spawn erscheinen lassen. Aktuelles Paper nutzt dafür das asynchrone
     * AsyncPlayerSpawnLocationEvent (Nachfolger des alten Spigot-PlayerSpawnLocationEvent).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (event.isNewPlayer() && module.settings().newPlayersToSpawn()) {
            event.setSpawnLocation(module.spawns().mainSpawn());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        String mode = module.settings().respawnMode();
        boolean override = switch (mode) {
            case "always" -> true;
            case "no-bed" -> !event.isBedSpawn() && !event.isAnchorSpawn();
            default -> false;
        };
        if (override) {
            event.setRespawnLocation(module.spawns().spawnFor(event.getRespawnLocation().getWorld()));
        }
    }

    /** /back: Startpunkt von Befehls- und Plugin-Teleports merken. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!module.settings().backOnTeleport()) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) < 4) {
            return; // winzige Korrekturen ignorieren
        }
        remember(event.getPlayer(), from);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (module.settings().backOnDeath() && player.hasPermission("nucleus.back.ondeath")) {
            remember(player, player.getLocation());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        module.tpa().forget(event.getPlayer().getUniqueId());
    }

    private void remember(Player player, Location loc) {
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        if (data != null) {
            data.backLocation(NamedLocation.of(loc));
        }
    }
}
