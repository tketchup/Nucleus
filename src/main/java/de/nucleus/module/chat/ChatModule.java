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
    record Settings(String format, String formatNoTag, String meFormat, String broadcastFormat,
                    String joinMessage, String firstJoinMessage, String quitMessage,
                    int nickMinLength, int nickMaxLength, Pattern nickPattern, String nickPrefix,
                    boolean tablistEnabled, String tablistFormat, String tablistFormatNoTag,
                    boolean colorsForEveryone, boolean tagSelfSelect, int tagMaxLength, int tagMaxBracket,
                    int maxMails,
                    long cooldownMillis, boolean blockRepeat, long repeatWindowMillis,
                    int spamMaxMessages, long spamWindowMillis) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    Configs.string(c, "format"),
                    Configs.string(c, "format-no-tag"),
                    Configs.string(c, "me-format"),
                    Configs.string(c, "broadcast-format"),
                    Configs.string(c, "join-message"),
                    Configs.string(c, "first-join-message"),
                    Configs.string(c, "quit-message"),
                    Math.max(1, c.getInt("nick.min-length")),
                    Math.max(1, c.getInt("nick.max-length")),
                    Pattern.compile(Configs.string(c, "nick.allowed-pattern")),
                    Configs.string(c, "nick.prefix"),
                    c.getBoolean("tablist.enabled"),
                    Configs.string(c, "tablist.format"),
                    Configs.string(c, "tablist.format-no-tag"),
                    c.getBoolean("colors-for-everyone"),
                    c.getBoolean("tags.self-select"),
                    Math.max(1, c.getInt("tags.max-length")),
                    Math.max(0, c.getInt("tags.max-bracket-length")),
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
    private final TagStore tags;
    /** Letzter Gesprächspartner für /reply (beide Richtungen). */
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public ChatModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.nicks = new NickIndex(plugin);
        this.tags = new TagStore(plugin);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("chat"));
        nicks.load();
        scope.listen(new ChatListener(plugin, this));
        // Nicknames von Spielern, die schon online sind; Tab-Liste, sobald die Tags geladen sind
        Bukkit.getOnlinePlayers().forEach(this::applyNick);
        tags.load(this::refreshAllTabs);
    }

    @Override
    public void disable() {
        scope.close();
        replyTargets.clear();
        // Anzeigenamen zurücksetzen, damit ein abgeschaltetes Modul keine Spuren hinterlässt
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.displayName(null);
            if (settings != null && settings.tablistEnabled()) {
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
        new TagCommands(plugin, this, kit).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    NickIndex nicks() {
        return nicks;
    }

    public TagStore tags() {
        return tags;
    }

    // --- Tags und Tab-Liste ---

    /** Gerenderter Tag eines Spielers oder null. Thread-sicher (Chat). */
    @Nullable Component tagOf(Player player) {
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        return data == null ? null : tags.component(data.tag());
    }

    /** Ob Nucleus die Tab-Liste gestaltet (dann gehört der AFK-Marker hierher). */
    public boolean tablistEnabled() {
        return settings != null && settings.tablistEnabled();
    }

    /** Tab-Listen-Name neu setzen: Format mit Tag, Anzeigename (Nick) und ggf. AFK-Marker. Hauptthread. */
    public void refreshTab(Player player) {
        if (!tablistEnabled()) {
            return;
        }
        Component tag = tagOf(player);
        Component name = plugin.messages().parse(tag != null ? settings.tablistFormat() : settings.tablistFormatNoTag(),
                "tag", tag != null ? tag : Component.empty(), "player", player.getName(),
                "display_name", player.displayName());
        var info = plugin.modules().get("info", de.nucleus.module.info.InfoModule.class);
        if (info != null && info.afk().isAfk(player)) {
            name = name.append(plugin.messages().get("afk.tablist-suffix"));
        }
        player.playerListName(name);
    }

    void refreshAllTabs() {
        Bukkit.getOnlinePlayers().forEach(this::refreshTab);
    }

    /** Nach Änderungen an chat.yml im Spiel (z. B. Import): Einstellungen neu, Tab-Liste auffrischen. */
    @Override
    public void reloadSettings() {
        boolean hadTablist = tablistEnabled();
        settings = Settings.load(plugin.config("chat"));
        if (hadTablist && !tablistEnabled()) {
            Bukkit.getOnlinePlayers().forEach(p -> p.playerListName(null));
        }
        refreshAllTabs();
    }

    /** Für den TagPlugin-Import: Tag aus einem YAML-Abschnitt übernehmen. */
    public boolean importTag(String id, org.bukkit.configuration.@Nullable ConfigurationSection section) {
        TagStore.Tag tag = TagStore.read(id, section);
        if (tag == null || !id.matches("[a-z0-9_-]{1,24}")) {
            return false;
        }
        tags.put(tag);
        return true;
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
        refreshTab(player);
    }
}
