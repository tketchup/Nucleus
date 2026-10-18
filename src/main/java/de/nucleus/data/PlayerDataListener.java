package de.nucleus.data;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Lädt Spielerdaten beim Login und schreibt sie beim Verlassen. */
final class PlayerDataListener implements Listener {

    private final PlayerDataStore store;

    PlayerDataListener(PlayerDataStore store) {
        this.store = store;
    }

    // MONITOR: erst laden, wenn kein anderes Plugin (Bans, Whitelist ...) den Login abgelehnt hat
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            store.loadForLogin(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        store.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.handleQuit(event.getPlayer());
    }
}
