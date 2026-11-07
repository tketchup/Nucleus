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

    record Settings(String line1, TextColor playerColor, String baseColor, boolean center, int maxWidth,
                    List<String> messages) {

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
            return new Settings(line1, playerColor, base, c.getBoolean("center"), Math.max(0, c.getInt("max-width")),
                    List.copyOf(messages));
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
    }

    /** Hauptthread: Namen von der Whitelist übernehmen (Einträge ohne bekannten Namen fallen weg). */
    private void refreshNames() {
        List<String> list = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
            String name = p.getName();
            if (name != null) {
                list.add(name);
            }
        }
        names = List.copyOf(list);
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> pool = names;
        String name = pool.isEmpty() ? FALLBACK_NAME : pool.get(random.nextInt(pool.size()));
        String message = s.messages().get(random.nextInt(s.messages().size()));

        Component line1 = MM.deserialize(toTags(s.line1()), Placeholder.unparsed("version", version));
        // Spielername in eigener Farbe; danach gilt wieder die Grundfarbe der Zeile
        Component line2 = MM.deserialize(s.baseColor() + toTags(message),
                Placeholder.component("player", Component.text(name, s.playerColor())));
        return centered(line1, s).append(Component.newline()).append(centered(line2, s));
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
