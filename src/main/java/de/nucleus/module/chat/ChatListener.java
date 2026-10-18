package de.nucleus.module.chat;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.PlayerData;
import de.nucleus.util.UserText;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat-Format (AsyncChatEvent + ChatRenderer), Ignorieren, Cooldown, Spam-Schutz, Join/Quit, MOTD. */
final class ChatListener implements Listener {

    private final NucleusPlugin plugin;
    private final ChatModule module;

    /** Spam-Schutz: pro Spieler letzte Nachricht und Zeitpunkte (Zugriff aus Chat-Threads). */
    private final Map<UUID, ChatHistory> history = new ConcurrentHashMap<>();

    private static final class ChatHistory {
        String lastMessage = "";
        long lastMessageAt;
        final Deque<Long> times = new ArrayDeque<>();
    }

    ChatListener(NucleusPlugin plugin, ChatModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /**
     * LOW: vor anderen Plugins prüfen (Cooldown/Spam brechen ab), Format per Renderer setzen.
     * Läuft asynchron: nur thread-sichere Daten anfassen.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        ChatModule.Settings s = module.settings();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (!checkSpam(sender, plain, s)) {
            event.setCancelled(true);
            return;
        }

        // Farben/Formatierung im Chat nur mit Permission (nucleus.chat.color/.hex/.format/.minimessage)
        // colors-for-everyone: Farben, Hex und Formatierung für alle (MiniMessage-Extras weiter nur per Recht)
        if (s.colorsForEveryone()) {
            event.message(UserText.parse(plain, true, true, true, sender.hasPermission("nucleus.chat.minimessage")));
        } else if (UserText.mayFormat(sender, "nucleus.chat")) {
            event.message(UserText.parse(plain, sender, "nucleus.chat"));
        }

        // Wer den Absender ignoriert, sieht die Nachricht nicht
        event.viewers().removeIf(viewer -> viewer instanceof Player p && ignores(p, sender));

        // Eigenes Format ohne Tag, damit keine leeren Klammern oder doppelten Leerzeichen entstehen
        Component tag = module.tagOf(sender);
        String format = tag != null ? s.format() : s.formatNoTag();
        Component tagComponent = tag != null ? tag : Component.empty();
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) ->
                plugin.messages().parse(format, "tag", tagComponent, "display_name", displayName,
                        "player", source.getName(), "world", source.getWorld().getName(), "message", message)));
    }

    /** @return false, wenn die Nachricht blockiert wird (Meldung an den Spieler inklusive) */
    private boolean checkSpam(Player sender, String plain, ChatModule.Settings s) {
        long now = System.currentTimeMillis();
        ChatHistory h = history.computeIfAbsent(sender.getUniqueId(), k -> new ChatHistory());
        synchronized (h) {
            if (s.cooldownMillis() > 0 && !sender.hasPermission("nucleus.chat.bypass.cooldown")
                    && now - h.lastMessageAt < s.cooldownMillis()) {
                plugin.messages().send(sender, "chat.cooldown",
                        "seconds", (s.cooldownMillis() - (now - h.lastMessageAt) + 999) / 1000);
                return false;
            }
            if (!sender.hasPermission("nucleus.chat.bypass.spam")) {
                if (s.blockRepeat() && plain.equalsIgnoreCase(h.lastMessage) && now - h.lastMessageAt < s.repeatWindowMillis()) {
                    plugin.messages().send(sender, "chat.repeat");
                    return false;
                }
                if (s.spamMaxMessages() > 0) {
                    while (!h.times.isEmpty() && now - h.times.peekFirst() > s.spamWindowMillis()) {
                        h.times.pollFirst();
                    }
                    if (h.times.size() >= s.spamMaxMessages()) {
                        plugin.messages().send(sender, "chat.spam");
                        return false;
                    }
                }
            }
            h.lastMessage = plain;
            h.lastMessageAt = now;
            h.times.addLast(now);
            return true;
        }
    }

    private boolean ignores(Player viewer, Player sender) {
        PlayerData data = plugin.playerData().getIfLoaded(viewer.getUniqueId());
        return data != null && data.isIgnoring(sender.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ChatModule.Settings s = module.settings();
        module.applyNick(player);

        String format = player.hasPlayedBefore() ? s.joinMessage() : s.firstJoinMessage();
        if (!"vanilla".equalsIgnoreCase(format)) {
            event.joinMessage(format.isEmpty() ? null
                    : plugin.messages().parse(format, "player", player.getName(), "display_name", player.displayName()));
        }

        long unread = plugin.playerData().get(player).unreadMails();
        if (unread > 0) {
            Component msg = plugin.messages().get("mail.unread", "count", unread)
                    .clickEvent(ClickEvent.runCommand("/mail read"));
            // kurz verzögert, damit es nicht in der MOTD untergeht
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(msg), 40L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String format = module.settings().quitMessage();
        if (!"vanilla".equalsIgnoreCase(format)) {
            event.quitMessage(format.isEmpty() ? null
                    : plugin.messages().parse(format, "player", player.getName(), "display_name", player.displayName()));
        }
        history.remove(player.getUniqueId());
        module.forget(player.getUniqueId());
    }
}
