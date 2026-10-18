package de.nucleus.module.lock;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modul "lock": Truhen und andere Behälter mit /lock sichern, optional mit Passwort (Eingabe per
 * Dialog, gespeichert nur als Hash). Schützt vor Öffnen, Abbauen, Trichtern und Explosionen.
 */
public final class LockModule implements Module {

    record Settings(Set<String> lockable, boolean autoLock, boolean protectHoppers, boolean protectExplosions,
                    boolean passwordsEnabled, int passwordMinLength, boolean rememberPermanently, int maxDistance) {

        static Settings load(FileConfiguration c) {
            Set<String> types = new HashSet<>();
            for (String s : c.getStringList("lockable")) {
                String key = s.toLowerCase(Locale.ROOT);
                types.add(key.contains(":") ? key : "minecraft:" + key);
            }
            return new Settings(Set.copyOf(types),
                    c.getBoolean("auto-lock"),
                    c.getBoolean("protect-hoppers"),
                    c.getBoolean("protect-explosions"),
                    c.getBoolean("password.enabled"),
                    Math.max(1, c.getInt("password.min-length")),
                    "permanent".equalsIgnoreCase(c.getString("password.remember")),
                    Math.clamp(c.getInt("max-distance"), 1, 10));
        }
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final LockService locks;
    private volatile Settings settings;

    public LockModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.locks = new LockService(plugin);
    }

    @Override
    public void enable() {
        settings = Settings.load(plugin.config("lock"));
        scope.listen(new LockListener(plugin, this));
    }

    @Override
    public void disable() {
        scope.close();
        locks.clearSessions();
    }

    @Override
    public void registerCommands(Commands registrar) {
        new LockCommands(plugin, this, new CommandKit(plugin, "lock")).register(registrar);
    }

    Settings settings() {
        return settings;
    }

    LockService locks() {
        return locks;
    }

    /**
     * Rechenintensives (Passwort-Hash, ~50 ms) im Hintergrund, Ergebnis danach auf dem Hauptthread.
     * Der Hauptthread ruckelt so nicht, wenn jemand ein Passwort eingibt.
     */
    <T> void offThread(java.util.function.Supplier<T> work, java.util.function.Consumer<T> then) {
        java.util.concurrent.CompletableFuture.supplyAsync(work)
                .thenAccept(result -> plugin.sync(() -> then.accept(result)));
    }

    /** Darf dieser Block laut Konfiguration gesperrt werden? */
    boolean isLockable(Block block) {
        return block.getState(false) instanceof TileState && settings.lockable().contains(LockService.typeKey(block));
    }
}
