package de.nucleus.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spielersuche. Online-Suche berücksichtigt Sichtbarkeit (versteckte Spieler werden für normale
 * Spieler nicht gefunden). Offline-Suche nutzt den Server-Namenscache, ohne Netzwerkzugriff.
 */
public final class PlayerLookup {

    private PlayerLookup() {
    }

    /**
     * Exakter Name (ohne Groß-/Kleinschreibung), dann exakter Anzeigename (Nickname, ohne Farben),
     * sonst eindeutiger Namens-Präfix.
     */
    public static Optional<Player> online(CommandSender viewer, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Player nickMatch = null;
        Player prefixMatch = null;
        boolean ambiguous = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!canSee(viewer, p)) {
                continue;
            }
            String pn = p.getName().toLowerCase(Locale.ROOT);
            if (pn.equals(lower)) {
                return Optional.of(p);
            }
            // Nick-Präfix wie "~" am Anfang ignorieren
            String nick = plainDisplayName(p).replaceFirst("^[^A-Za-z0-9_]+", "");
            if (nickMatch == null && nick.toLowerCase(Locale.ROOT).equals(lower)) {
                nickMatch = p;
            }
            if (pn.startsWith(lower)) {
                ambiguous = prefixMatch != null;
                prefixMatch = p;
            }
        }
        if (nickMatch != null) {
            return Optional.of(nickMatch);
        }
        return ambiguous ? Optional.empty() : Optional.ofNullable(prefixMatch);
    }

    /** Anzeigename ohne Formatierung (z. B. Nickname ohne Farben und ohne Nick-Präfix-Zeichen). */
    public static String plainDisplayName(Player p) {
        return PlainTextComponentSerializer.plainText().serialize(p.displayName());
    }

    /** Online (exakt) oder ein bekannter Offline-Spieler, der schon einmal auf dem Server war. */
    public static Optional<OfflinePlayer> known(CommandSender viewer, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && canSee(viewer, exact)) {
            return Optional.of(exact);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return Optional.of(cached);
        }
        return online(viewer, name).map(p -> p);
    }

    /** Namen aller Spieler, die der Betrachter sehen kann (für Tab-Completion). */
    public static List<String> visibleNames(CommandSender viewer) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (canSee(viewer, p)) {
                names.add(p.getName());
            }
        }
        return names;
    }

    public static boolean canSee(CommandSender viewer, Player target) {
        return !(viewer instanceof Player p) || p.canSee(target);
    }
}
