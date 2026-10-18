package de.nucleus.module.moderation;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import de.nucleus.NucleusPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Vanish beim Beitreten/Verlassen, kein Aufheben, keine Mob-Ziele, ausgeblendet in der Serverliste. */
final class VanishListener implements Listener {

    private final NucleusPlugin plugin;
    private final ModerationModule module;

    VanishListener(NucleusPlugin plugin, ModerationModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** HIGHEST: nach dem chat-Modul (HIGH), damit dessen Beitrittsnachricht unterdrückt werden kann. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        VanishService vanish = module.vanish();
        vanish.hideVanishedFrom(player);
        if (module.settings().vanishPersist() && plugin.playerData().get(player).vanished()
                && player.hasPermission("nucleus.vanish")) {
            vanish.set(player, true, false);
            event.joinMessage(null);
            plugin.messages().send(player, "moderation.vanish-still");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (module.vanish().isVanished(event.getPlayer())) {
            event.quitMessage(null);
            module.vanish().quit(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (module.settings().vanishNoPickup() && event.getEntity() instanceof Player p && module.vanish().isVanished(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (module.settings().vanishNoMobTarget() && event.getTarget() instanceof Player p && module.vanish().isVanished(p)) {
            event.setCancelled(true);
        }
    }

    /** Versteckte Spieler zählen in der Serverliste nicht mit (asynchron). */
    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        int hidden = module.vanish().vanishedIds().size();
        if (hidden > 0) {
            event.setNumPlayers(Math.max(0, event.getNumPlayers() - hidden));
            event.getListedPlayers().removeIf(info -> module.vanish().vanishedIds().contains(info.id()));
        }
    }
}
