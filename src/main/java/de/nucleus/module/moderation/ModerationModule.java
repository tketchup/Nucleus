package de.nucleus.module.moderation;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import de.nucleus.data.LocationStore;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modul "moderation": Kick, Bans (Paper-Ban-API), Mute, Freeze, Jail, Vanish, Invsee, Enderchest,
 * Clearinventory, Sudo, CommandSpy. Alle Aktionen landen in moderation.log.
 */
public final class ModerationModule implements Module {

    record Settings(Set<String> muteBlockedCommands, int jailRadius, Set<String> jailAllowedCommands,
                    boolean vanishPersist, boolean vanishFakeMessages, boolean vanishNoPickup,
                    boolean vanishNoMobTarget) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    lower(c.getStringList("mute.blocked-commands")),
                    Math.max(1, c.getInt("jail.radius")),
                    lower(c.getStringList("jail.allowed-commands")),
                    c.getBoolean("vanish.persist"),
                    c.getBoolean("vanish.fake-join-quit"),
                    c.getBoolean("vanish.prevent-pickup"),
                    c.getBoolean("vanish.prevent-mob-target"));
        }

        private static Set<String> lower(java.util.List<String> list) {
            Set<String> set = new HashSet<>();
            list.forEach(s -> set.add(s.toLowerCase(Locale.ROOT)));
            return Set.copyOf(set);
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final ModerationLog log;
    private final LocationStore jails;
    private final VanishService vanish;
    private final JailService jailService;
    private volatile Settings settings;

    public ModerationModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.log = new ModerationLog(plugin);
        this.jails = new LocationStore(plugin, "jails.yml", "jails");
        this.vanish = new VanishService(plugin, this);
        this.jailService = new JailService(plugin, this);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("moderation"));
        jails.load();
        scope.listen(new ModerationListener(plugin, this));
        scope.listen(new VanishListener(plugin, this));
        scope.timer(jailService::tick, 20L, 20L);
        vanish.restoreOnline();
    }

    @Override
    public void disable() {
        scope.close();
        vanish.revealAll();
    }

    @Override
    public void registerCommands(Commands registrar) {
        CommandKit kit = new CommandKit(plugin, "moderation");
        new PunishCommands(plugin, this, kit).register(registrar);
        new ControlCommands(plugin, this, kit).register(registrar);
        new InventoryCommands(plugin, this, kit).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    ModerationLog log() {
        return log;
    }

    /** Öffentlich für den EssentialsX-Import. */
    public LocationStore jails() {
        return jails;
    }

    JailService jailService() {
        return jailService;
    }

    /** Öffentlich, damit andere Module (z. B. gameplay) den Vanish-Status kennen. */
    public VanishService vanish() {
        return vanish;
    }
}
