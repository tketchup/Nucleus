package de.nucleus.module.serverlist;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Setzt bei jedem Ping der Serverliste die MOTD neu. Läuft nicht auf dem Hauptthread. */
final class ServerListMotdListener implements Listener {

    private final ServerListMotdModule module;
    private final java.util.logging.Logger log;
    /** Einen Fehler nur einmal melden, sonst füllt jeder Ping das Log. */
    private volatile boolean warned;

    ServerListMotdListener(ServerListMotdModule module, java.util.logging.Logger log) {
        this.module = module;
        this.log = log;
    }

    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        try {
            Component motd = module.build();
            if (motd != null) {
                event.motd(motd);
            }
        } catch (RuntimeException e) {
            // Im Zweifel die normale Server-MOTD lassen, der Ping darf nie scheitern
            if (!warned) {
                warned = true;
                log.warning("serverlist-motd: MOTD konnte nicht erzeugt werden: " + e);
            }
        }
    }
}
