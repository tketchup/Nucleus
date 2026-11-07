package de.nucleus.module.serverlist;

import de.nucleus.NucleusPlugin;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import de.nucleus.util.Configs;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Modul "serverlist-motd": zweizeilige MOTD in der Serverliste. Zeile 1 mit Servername und Version,
 * Zeile 2 eine zufällige (erfundene) Meldung über einen Spieler von der Whitelist, bei jedem Ping neu.
 * Keine Befehle; Änderungen an der Config mit /nucleus reload.
 */
public final class ServerListMotdModule implements Module {

    /** Whitelist neu einlesen: alle 5 Minuten. */
    private static final long REFRESH_TICKS = 6000L;
    private static final String FALLBACK_NAME = "Jemand";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Versuche pro Ping, eine Kombination aus Name und Meldung zu finden, die in die Zeile passt. */
    private static final int FIT_ATTEMPTS = 25;

    record Settings(String line1, TextColor playerColor, String baseColor, boolean center, int maxWidth,
                    List<String> messages, boolean whitelistNames, List<String> extraNames, int extraChance) {

        static Settings load(FileConfiguration c, Logger log) {
            String base = Configs.string(c, "base-color").strip();
            // "#9aa5b1" ohne spitze Klammern auch zulassen
            if (base.startsWith("#")) {
                base = "<" + base + ">";
            }
            String colorText = Configs.string(c, "player-color").strip();
            TextColor playerColor = colorText.startsWith("#") ? TextColor.fromHexString(colorText)
                    : NamedTextColor.NAMES.value(colorText.toLowerCase(java.util.Locale.ROOT));
            if (playerColor == null) {
                log.warning("serverlist-motd: player-color '" + colorText + "' ist keine Farbe, nutze #ffd166.");
                playerColor = TextColor.color(0xFFD166);
            }

            // Kaputte Vorlagen schon hier aussortieren, damit der Ping nie an ihnen scheitert
            List<String> messages = new ArrayList<>();
            for (String m : c.getStringList("messages")) {
                if (m != null && !m.isBlank() && parses(base + toTags(m), log, "messages: " + m)) {
                    messages.add(m);
                }
            }
            String line1 = Configs.string(c, "line1");
            if (!parses(toTags(line1), log, "line1")) {
                line1 = "";
            }
            List<String> extra = new ArrayList<>();
            for (String n : c.getStringList("names.extra")) {
                if (n != null && !n.isBlank()) {
                    extra.add(n.strip());
                }
            }
            return new Settings(line1, playerColor, base, c.getBoolean("center"), Math.max(0, c.getInt("max-width")),
                    List.copyOf(messages), c.getBoolean("names.whitelist"), List.copyOf(extra),
                    Math.clamp(c.getInt("names.extra-chance"), 0, 100));
        }

        private static boolean parses(String template, Logger log, String what) {
            try {
                MM.deserialize(template, Placeholder.unparsed("player", FALLBACK_NAME),
                        Placeholder.unparsed("version", "0"));
                return true;
            } catch (RuntimeException e) {
                log.warning("serverlist-motd: " + what + " ist kein gültiges MiniMessage und wird übersprungen ("
                        + e.getMessage() + ")");
                return false;
            }
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private volatile Settings settings;
    /** Namen von der Whitelist. Volatile: der Ping kommt nicht vom Hauptthread. */
    private volatile List<String> names = List.of();
    private final String version = Bukkit.getMinecraftVersion();

    public ServerListMotdModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("serverlist-motd"), plugin.getLogger());
        refreshNames();
        scope.timer(this::refreshNames, REFRESH_TICKS, REFRESH_TICKS);
        scope.listen(new ServerListMotdListener(this, plugin.getLogger()));
    }

    @Override
    public void disable() {
        scope.close();
        names = List.of();
    }

    @Override
    public void registerCommands(Commands registrar) {
        // keine eigenen Befehle: /nucleus reload liest die Einstellungen neu
    }

    @Override
    public void reloadSettings() {
        settings = Settings.load(plugin.config("serverlist-motd"), plugin.getLogger());
        refreshNames();
    }

    /** Hauptthread: Namen von der Whitelist übernehmen (Einträge ohne bekannten Namen fallen weg). */
    private void refreshNames() {
        List<String> list = new ArrayList<>();
        if (settings.whitelistNames()) {
            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                String name = p.getName();
                if (name != null) {
                    list.add(name);
                }
            }
        }
        names = List.copyOf(list);
    }

    /**
     * Zufälliger Name: mit names.extra-chance Prozent aus names.extra, sonst von der Whitelist. Ist eine der
     * beiden Listen leer, kommt der Name immer aus der anderen.
     */
    private static String randomName(Settings s, List<String> whitelist, ThreadLocalRandom random) {
        List<String> extra = s.extraNames();
        boolean useExtra;
        if (whitelist.isEmpty()) {
            useExtra = true;
        } else if (extra.isEmpty()) {
            useExtra = false;
        } else {
            useExtra = random.nextInt(100) < s.extraChance();
        }
        List<String> pool = useExtra ? extra : whitelist;
        return pool.isEmpty() ? FALLBACK_NAME : pool.get(random.nextInt(pool.size()));
    }

    /**
     * Die MOTD für einen Ping, bei jedem Aufruf mit neuer zufälliger Meldung und neuem Namen.
     * Null, wenn nichts gezeigt werden soll (leere Meldungsliste): dann bleibt die MOTD des Servers.
     */
    @Nullable Component build() {
        Settings s = settings;
        if (s.messages().isEmpty()) {
            return null;
        }
        Component line1 = MM.deserialize(toTags(s.line1()), Placeholder.unparsed("version", version));
        return centered(line1, s).append(Component.newline()).append(centered(randomLine2(s), s));
    }

    /**
     * Zufällige Meldung mit zufälligem Namen. Zu lange Zeilen schneidet der Client ab, deshalb wird eine
     * Kombination gesucht, die in max-width passt; klappt das nicht, gewinnt die kürzeste gefundene.
     */
    private Component randomLine2(Settings s) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> pool = names;
        Component best = null;
        int bestWidth = Integer.MAX_VALUE;
        for (int i = 0; i < FIT_ATTEMPTS; i++) {
            String name = randomName(s, pool, random);
            String message = s.messages().get(random.nextInt(s.messages().size()));
            // Spielername in eigener Farbe; danach gilt wieder die Grundfarbe der Zeile
            Component line = MM.deserialize(s.baseColor() + toTags(message),
                    Placeholder.component("player", Component.text(name, s.playerColor())));
            int width = MotdCentering.width(PlainTextComponentSerializer.plainText().serialize(line));
            if (s.maxWidth() <= 0 || width <= s.maxWidth()) {
                return line;
            }
            if (width < bestWidth) {
                best = line;
                bestWidth = width;
            }
        }
        return best;
    }

    private static Component centered(Component line, Settings s) {
        if (!s.center()) {
            return line;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        int spaces = MotdCentering.spaces(plain, s.maxWidth());
        return spaces == 0 ? line : Component.text(" ".repeat(spaces)).append(line);
    }

    /** {player} und {version} aus der Config werden zu MiniMessage-Platzhaltern. */
    private static String toTags(String template) {
        return template.replace("{player}", "<player>").replace("{version}", "<version>");
    }
}
