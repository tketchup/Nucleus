package de.nucleus.module.info;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Modul "info": /list, /seen, /whois, /getpos, /near, /ping, /playtime, /compass, /depth, /motd, /rules und AFK. */
public final class InfoModule implements Module {

    record Settings(long autoAfkMillis, long kickMillis, boolean tablistMarker, boolean ignoreSleep,
                    boolean broadcastAfk, int nearDefault, int nearMax,
                    boolean motdOnJoin, List<String> motd, List<String> rules) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    Math.max(0, c.getLong("afk.auto-afk-minutes")) * 60_000L,
                    Math.max(0, c.getLong("afk.kick-after-minutes")) * 60_000L,
                    c.getBoolean("afk.tablist-marker"),
                    c.getBoolean("afk.ignore-sleep"),
                    c.getBoolean("afk.broadcast"),
                    Math.max(1, c.getInt("near.default-radius")),
                    Math.max(1, c.getInt("near.max-radius")),
                    c.getBoolean("motd.on-join"),
                    c.getStringList("motd.lines"),
                    c.getStringList("rules"));
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final AfkService afk;
    /** Beitrittszeit für "/seen: online seit". */
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public InfoModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.afk = new AfkService(plugin, this);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("info"));
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            joinTimes.putIfAbsent(p.getUniqueId(), now);
            afk.activity(p);
        }
        scope.listen(new InfoListener(this));
        scope.timer(afk::tick, 20L, 20L);
    }

    @Override
    public void disable() {
        scope.close();
        afk.clearAll();
    }

    @Override
    public void registerCommands(Commands registrar) {
        CommandKit kit = new CommandKit(plugin, "info");
        new InfoCommands(plugin, this, kit).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    /** Nach Änderungen im Spiel (z. B. /motd set) die Einstellungen sofort neu übernehmen. */
    @Override
    public void reloadSettings() {
        settings = Settings.load(plugin.config("info"));
    }

    /** Öffentlich, damit andere Module (z. B. Moderation) den AFK-Status abfragen können. */
    public AfkService afk() {
        return afk;
    }

    long joinTime(UUID player) {
        return joinTimes.getOrDefault(player, System.currentTimeMillis());
    }

    void joined(Player player) {
        joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    void left(Player player) {
        joinTimes.remove(player.getUniqueId());
    }

    NucleusPlugin plugin() {
        return plugin;
    }

    /**
     * Mehrzeiliger Text aus der Config (MOTD, Regeln) mit den Platzhaltern &lt;player&gt;, &lt;online&gt;,
     * &lt;max&gt;, &lt;world&gt;, &lt;worldtime&gt; (Uhrzeit der Welt), &lt;time&gt; und &lt;date&gt; (echte Uhrzeit).
     */
    void sendLines(org.bukkit.command.CommandSender to, List<String> lines) {
        org.bukkit.World world = to instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String worldTime = world.isFixedTime() ? "–" : de.nucleus.util.GameTime.clock(world.getTime());
        for (String line : lines) {
            to.sendMessage(plugin.messages().parse(line, "player", to.getName(),
                    "online", Bukkit.getOnlinePlayers().size(), "max", Bukkit.getMaxPlayers(),
                    "world", world.getName(), "worldtime", worldTime,
                    "time", String.format(java.util.Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute()),
                    "date", plugin.messages().date(System.currentTimeMillis()).split(" ")[0]));
        }
    }
}
