package de.nucleus.module.gameplay;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modul "gameplay": Spielmodus, Fly, God, Heal, Feed, Speed, Zeit/Wetter, Items, Werkbänke,
 * Powertools, Spawnmob. Überschreibt Vanilla-Befehle wie /gamemode, /time, /weather, /give nur,
 * solange es aktiv ist (Vanilla bleibt unter /minecraft:... erreichbar).
 */
public final class GameplayModule implements Module {

    record Settings(boolean persistGod, boolean persistFly, boolean godNoHunger, int maxGive, int maxSpawnmob) {

        static Settings load(FileConfiguration c) {
            return new Settings(
                    c.getBoolean("persist-god"),
                    c.getBoolean("persist-fly"),
                    c.getBoolean("god-no-hunger"),
                    Math.max(1, c.getInt("max-give-amount")),
                    Math.max(1, c.getInt("max-spawnmob")));
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    /** Spieler im God-Modus (auch ohne Speicherung über Neustarts). Damage-Events laufen auf dem Hauptthread. */
    private final Set<UUID> gods = ConcurrentHashMap.newKeySet();
    private volatile Settings settings;

    public GameplayModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("gameplay"));
        scope.listen(new GameplayListener(plugin, this));
        Bukkit.getOnlinePlayers().forEach(this::restore);
    }

    @Override
    public void disable() {
        scope.close();
        gods.clear();
    }

    @Override
    public void registerCommands(Commands registrar) {
        CommandKit kit = new CommandKit(plugin, "gameplay");
        new PlayerCommands(plugin, this, kit).register(registrar);
        new WorldCommands(plugin, kit).register(registrar);
        new ItemCommands(plugin, this, kit).register(registrar);
        new MenuCommands(plugin, kit).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    boolean isGod(Player player) {
        return gods.contains(player.getUniqueId());
    }

    void setGod(Player player, boolean value) {
        if (value) {
            gods.add(player.getUniqueId());
        } else {
            gods.remove(player.getUniqueId());
        }
        plugin.playerData().get(player).god(value && settings.persistGod());
    }

    void setFly(Player player, boolean value) {
        player.setAllowFlight(value);
        if (!value) {
            player.setFlying(false);
        }
        plugin.playerData().get(player).fly(value && settings.persistFly());
    }

    /** God/Fly aus den gespeicherten Daten wiederherstellen (Beitritt, Weltwechsel, Spielmoduswechsel). */
    void restore(Player player) {
        var data = plugin.playerData().get(player);
        if (settings.persistGod() && data.god() && player.hasPermission("nucleus.god")) {
            gods.add(player.getUniqueId());
        }
        if (settings.persistFly() && data.fly() && player.hasPermission("nucleus.fly") && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
    }

    void forget(Player player) {
        gods.remove(player.getUniqueId());
    }
}
