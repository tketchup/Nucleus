package de.nucleus.module.info;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/** Erkennt Aktivität für das AFK-System und merkt sich Beitrittszeiten. */
final class InfoListener implements Listener {

    private final InfoModule module;

    InfoListener(InfoModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Umschauen zählt immer, Positionsänderung nur ohne Fahrzeug (AFK-Fallen mit Wasser/Loren)
        if (event.hasChangedOrientation() || (event.hasChangedBlock() && !event.getPlayer().isInsideVehicle())) {
            module.afk().activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        // asynchron: zurück auf den Hauptthread
        module.plugin().sync(() -> module.afk().activity(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().toLowerCase(Locale.ROOT);
        if (!label.equals("/afk") && !label.startsWith("/afk ") && !label.startsWith("/nucleus:afk")) {
            module.afk().activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        module.afk().activity(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        module.joined(player);
        module.afk().activity(player);
        if (module.settings().motdOnJoin()) {
            // kurz verzögert, damit die MOTD nach den Beitrittsnachrichten erscheint
            Bukkit.getScheduler().runTaskLater(module.plugin(), () -> {
                if (player.isOnline()) {
                    module.sendLines(player, module.settings().motd());
                }
            }, 10L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        module.left(event.getPlayer());
        module.afk().quit(event.getPlayer());
    }
}
