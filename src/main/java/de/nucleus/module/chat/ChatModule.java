package de.nucleus.module.chat;

import de.nucleus.NucleusPlugin;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import de.nucleus.data.PlayerData;
import de.nucleus.util.Configs;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Modul "chat": Chat-Format, private Nachrichten, Ignorieren, SocialSpy, Nicknames, /me, /broadcast,
 * Mail sowie Beitritts-/Austrittsnachrichten. Standardmäßig aus.
 */
public final class ChatModule implements Module {

    /** Schlüssel für die Konsole in der /reply-Zuordnung. */
    static final UUID CONSOLE = new UUID(0, 0);

    /** Einstellungen aus config.yml (volatile, weil der Chat asynchron liest). */
    record Settings(String format, String meFormat, String broadcastFormat,
                    String joinMessage, String firstJoinMessage, String quitMessage,
                    int nickMinLength, int nickMaxLength, Pattern nickPattern, String nickPrefix,
                    boolean nickInTablist, int maxMails,
                    long cooldownMillis, boolean blockRepeat, long repeatWindowMillis,
                    int spamMaxMessages, long spamWindowMillis) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    Configs.string(c, "format"),
                    Configs.string(c, "me-format"),
                    Configs.string(c, "broadcast-format"),
                    Configs.string(c, "join-message"),
                    Configs.string(c, "first-join-message"),
                    Configs.string(c, "quit-message"),
                    Math.max(1, c.getInt("nick.min-length")),
                    Math.max(1, c.getInt("nick.max-length")),
                    Pattern.compile(Configs.string(c, "nick.allowed-pattern")),
                    Configs.string(c, "nick.prefix"),
                    c.getBoolean("nick.show-in-tablist"),
                    Math.max(1, c.getInt("mail.max-per-player")),
                    Math.max(0, c.getLong("cooldown-seconds")) * 1000L,
                    c.getBoolean("spam.block-repeat"),
                    Math.max(0, c.getLong("spam.repeat-window-seconds")) * 1000L,
                    Math.max(0, c.getInt("spam.max-messages")),
                    Math.max(1, c.getLong("spam.window-seconds")) * 1000L);
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final NickIndex nicks;
    /** Letzter Gesprächspartner für /reply (beide Richtungen). */
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public ChatModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.nicks = new NickIndex(plugin);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("chat"));
        nicks.load();
        scope.listen(new ChatListener(plugin, this));
        // Nicknames von Spielern, die schon online sind
        Bukkit.getOnlinePlayers().forEach(this::applyNick);
    }

    @Override
    public void disable() {
        scope.close();
        replyTargets.clear();
        // Anzeigenamen zurücksetzen, damit ein abgeschaltetes Modul keine Spuren hinterlässt
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.displayName(null);
            if (settings != null && settings.nickInTablist()) {
                p.playerListName(null);
            }
        }
    }

    @Override
    public void registerCommands(Commands registrar) {
        var kit = new de.nucleus.command.CommandKit(plugin, "chat");
        new MessageCommands(plugin, this, kit).register(registrar);
        new NickCommands(plugin, this, kit).register(registrar);
        new AnnounceCommands(plugin, this, kit).register(registrar);
        new MailCommands(plugin, this, kit).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    NickIndex nicks() {
        return nicks;
    }

    // --- /reply ---

    static UUID key(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : CONSOLE;
    }

    void rememberConversation(CommandSender a, CommandSender b) {
        replyTargets.put(key(a), key(b));
        replyTargets.put(key(b), key(a));
    }

    @Nullable UUID replyTarget(CommandSender sender) {
        return replyTargets.get(key(sender));
    }

    void forget(UUID player) {
        replyTargets.remove(player);
    }

    // --- Nicknames ---

    /** Für den Import: Nick ins Verzeichnis eintragen, damit die Eindeutigkeitsprüfung ihn kennt. */
    public void indexNick(UUID player, String plainNick) {
        nicks.set(player, plainNick);
    }

    /**
     * Setzt den Anzeigenamen nach den gespeicherten Daten (Hauptthread). Der gespeicherte Nick ist
     * schon beim Setzen geprüft worden und wird deshalb mit vollem MiniMessage gelesen.
     */
    void applyNick(Player player) {
        PlayerData data = plugin.playerData().get(player);
        String nick = data.nick();
        Component display = nick == null ? null
                : Component.text(settings.nickPrefix()).append(MiniMessage.miniMessage().deserialize(nick));
        player.displayName(display);
        if (settings.nickInTablist()) {
            player.playerListName(display);
        }
    }
}
