package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import de.nucleus.util.Configs;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Locale;
import java.util.regex.Pattern;

/** Modul "teleport": Homes, Warps, Spawn, TPA, Admin-Teleports und /back. */
public final class TeleportModule implements Module {

    /** Erlaubte Namen für Homes und Warps. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** Einstellungen aus config.yml, beim Aktivieren eingelesen. volatile: auch async gelesen. */
    public record Settings(int defaultHomeLimit, boolean perWarpPermission, boolean spawnPerWorld,
                           boolean newPlayersToSpawn, String respawnMode, int tpaExpireSeconds,
                           boolean backOnTeleport, boolean backOnDeath, boolean guiEnabled) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    Math.max(0, c.getInt("homes.default-limit")),
                    c.getBoolean("warps.per-warp-permission"),
                    c.getBoolean("spawn.per-world"),
                    c.getBoolean("spawn.teleport-new-players"),
                    Configs.string(c, "spawn.respawn-at-spawn").toLowerCase(Locale.ROOT),
                    Math.max(10, c.getInt("tpa.expire-seconds")),
                    c.getBoolean("back.record-teleports"),
                    c.getBoolean("back.record-death"),
                    c.getBoolean("gui.enabled"));
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final WarpStore warps;
    private final SpawnStore spawns;
    private final TpaManager tpa;
    private final TeleportMenus menus;
    private volatile Settings settings;

    public TeleportModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.warps = new WarpStore(plugin);
        this.spawns = new SpawnStore(plugin, this);
        this.tpa = new TpaManager(plugin, this);
        this.menus = new TeleportMenus(plugin, this);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("teleport"));
        warps.load();
        spawns.load();
        scope.listen(new TeleportListener(plugin, this));
        scope.timer(tpa::purgeExpired, 20L, 20L);
    }

    @Override
    public void disable() {
        scope.close();
        tpa.clear();
    }

    @Override
    public void registerCommands(Commands registrar) {
        CommandKit kit = new CommandKit(plugin, "teleport");
        new HomeCommands(plugin, this, kit).register(registrar);
        new WarpCommands(plugin, this, kit).register(registrar);
        new SpawnCommands(plugin, this, kit).register(registrar);
        new TpaCommands(plugin, this, kit).register(registrar);
        new AdminTeleportCommands(plugin, this, kit).register(registrar);
    }

    public Settings settings() {
        return settings;
    }

    public WarpStore warps() {
        return warps;
    }

    public SpawnStore spawns() {
        return spawns;
    }

    public TpaManager tpa() {
        return tpa;
    }

    TeleportMenus menus() {
        return menus;
    }

    /** Setzt ein Home an der Position des Spielers (Befehl und GUI). Wirft CommandFail bei Fehlern. */
    void setHome(Player player, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new de.nucleus.command.CommandFail("home.invalid-name");
        }
        de.nucleus.data.PlayerData data = plugin.playerData().get(player);
        int limit = homeLimit(player);
        if (data.home(name) == null && data.homes().size() >= limit) {
            throw new de.nucleus.command.CommandFail("home.limit", "count", data.homes().size(), "limit", limit);
        }
        data.setHome(name, de.nucleus.data.NamedLocation.of(player.getLocation()));
        plugin.messages().send(player, "home.set", "name", name);
    }

    /** Darf der Absender diesen Warp sehen und benutzen (optionale Permission pro Warp)? */
    boolean canUseWarp(org.bukkit.command.CommandSender sender, String warp) {
        return !settings.perWarpPermission() || sender.hasPermission("nucleus.warp." + warp.toLowerCase(Locale.ROOT));
    }

    /**
     * Home-Limit: Standard aus der Config, erhöht durch nucleus.homes.&lt;zahl&gt; (größte Zahl gewinnt),
     * unbegrenzt mit nucleus.homes.unlimited.
     */
    public int homeLimit(Player player) {
        if (player.hasPermission("nucleus.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int limit = settings.defaultHomeLimit();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("nucleus.homes.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring("nucleus.homes.".length())));
                } catch (NumberFormatException ignored) {
                    // andere Unter-Permission
                }
            }
        }
        return limit;
    }
}
