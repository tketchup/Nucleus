package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Offene TPA-Anfragen. Nur Hauptthread. */
public final class TpaManager {

    /**
     * @param here true = /tpahere (Empfänger soll zum Absender), false = /tpa (Absender will zum Empfänger)
     */
    public record Request(UUID from, UUID to, boolean here, long expiresAt) {
    }

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    /** Empfänger → (Absender → Anfrage), in Eingangsreihenfolge. */
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();

    TpaManager(NucleusPlugin plugin, TeleportModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    public Request send(Player from, Player to, boolean here) {
        long expires = System.currentTimeMillis() + module.settings().tpaExpireSeconds() * 1000L;
        Request request = new Request(from.getUniqueId(), to.getUniqueId(), here, expires);
        LinkedHashMap<UUID, Request> map = incoming.computeIfAbsent(to.getUniqueId(), k -> new LinkedHashMap<>());
        map.remove(from.getUniqueId()); // neue Anfrage ersetzt alte und kommt ans Ende
        map.put(from.getUniqueId(), request);
        return request;
    }

    /** Nimmt eine Anfrage heraus: von einem bestimmten Absender oder die neueste. */
    public @Nullable Request take(UUID to, @Nullable UUID from) {
        LinkedHashMap<UUID, Request> map = incoming.get(to);
        if (map == null || map.isEmpty()) {
            return null;
        }
        Request request;
        if (from != null) {
            request = map.remove(from);
        } else {
            request = map.sequencedValues().getLast();
            map.remove(request.from());
        }
        if (map.isEmpty()) {
            incoming.remove(to);
        }
        return request != null && request.expiresAt() > System.currentTimeMillis() ? request : null;
    }

    /** Ausgehende Anfragen eines Spielers zurückziehen. */
    public List<Request> cancelOutgoing(UUID from, @Nullable UUID to) {
        List<Request> removed = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Request>>> it = incoming.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, Request>> e = it.next();
            if (to != null && !e.getKey().equals(to)) {
                continue;
            }
            Request r = e.getValue().remove(from);
            if (r != null) {
                removed.add(r);
            }
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
        return removed;
    }

    /** Absender mit offenen Anfragen an diesen Spieler (für Tab-Completion). */
    public List<String> requesterNames(UUID to) {
        List<String> names = new ArrayList<>();
        LinkedHashMap<UUID, Request> map = incoming.get(to);
        if (map != null) {
            for (UUID id : map.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    names.add(p.getName());
                }
            }
        }
        return names;
    }

    /** Jede Sekunde: abgelaufene Anfragen entfernen und beide Seiten informieren. */
    void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Iterator<LinkedHashMap<UUID, Request>> it = incoming.values().iterator(); it.hasNext(); ) {
            LinkedHashMap<UUID, Request> map = it.next();
            map.values().removeIf(r -> {
                if (r.expiresAt() > now) {
                    return false;
                }
                Player from = Bukkit.getPlayer(r.from());
                Player to = Bukkit.getPlayer(r.to());
                if (from != null) {
                    plugin.messages().send(from, "tpa.expired-sender", "player", to != null ? to.getName() : "?");
                }
                if (to != null) {
                    plugin.messages().send(to, "tpa.expired-target", "player", from != null ? from.getName() : "?");
                }
                return true;
            });
            if (map.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Spieler verlässt den Server: alle Anfragen von und an ihn verwerfen. */
    void forget(UUID player) {
        incoming.remove(player);
        cancelOutgoing(player, null);
    }

    void clear() {
        incoming.clear();
    }
}
