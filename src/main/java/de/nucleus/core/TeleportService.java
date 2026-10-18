package de.nucleus.core;

import de.nucleus.NucleusPlugin;
import de.nucleus.util.SafeLocationFinder;
import de.nucleus.util.TimeParser;
import de.nucleus.util.WorldBlockView;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Zentraler Teleport-Dienst für alle Module.
 *
 * <p>Ablauf: Prüfungen (Cooldown, Welt-Berechtigung, Weltgrenze) → optional Wartezeit mit Abbruch bei
 * Bewegung/Schaden → Chunk asynchron laden → sichere Position suchen → teleportAsync.
 */
public final class TeleportService implements Listener {

    public static final String BYPASS_WARMUP = "nucleus.teleport.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "nucleus.teleport.bypass.cooldown";
    public static final String BYPASS_BORDER = "nucleus.teleport.bypass.worldborder";

    /**
     * @param cooldownKey Schlüssel für Cooldown (config teleport.cooldowns.&lt;key&gt;), null = kein Cooldown
     * @param warmup      Wartezeit anwenden
     * @param safe        sichere Position suchen
     */
    public record Options(@Nullable String cooldownKey, boolean warmup, boolean safe) {
        /** Spieler-Befehl: Wartezeit, Cooldown, sichere Position. */
        public static Options player(String cooldownKey) {
            return new Options(cooldownKey, true, true);
        }

        /** Admin-Befehl: sofort, ohne Cooldown, aber sicher. */
        public static Options admin() {
            return new Options(null, false, true);
        }

        /** Exakt zu einem Spieler: der steht schon an einer gültigen Stelle. */
        public static Options exact() {
            return new Options(null, false, false);
        }
    }

    private final NucleusPlugin plugin;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    private boolean warmupEnabled;
    private boolean cooldownsEnabled;
    private boolean countdownActionbar;
    private int warmupSeconds;
    private boolean cancelOnMove;
    private boolean cancelOnDamage;
    private boolean safeEnabled;
    private int safeRadius;
    private boolean crossWorldPermission;
    private boolean allowOutsideBorder;

    public TeleportService(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
        HandlerList.unregisterAll(this);
    }

    public void reload() {
        FileConfiguration c = plugin.config("teleport");
        warmupEnabled = c.getBoolean("warmup-enabled");
        cooldownsEnabled = c.getBoolean("cooldowns-enabled");
        countdownActionbar = c.getBoolean("countdown-actionbar");
        warmupSeconds = Math.max(0, c.getInt("warmup-seconds"));
        cancelOnMove = c.getBoolean("cancel-on-move");
        cancelOnDamage = c.getBoolean("cancel-on-damage");
        safeEnabled = c.getBoolean("safe-location");
        safeRadius = Math.clamp(c.getInt("safe-search-radius"), 0, 8);
        crossWorldPermission = c.getBoolean("cross-world.require-permission");
        allowOutsideBorder = c.getBoolean("allow-outside-worldborder");
    }

    public void teleport(Player player, Location target, Options options, @Nullable Runnable onSuccess) {
        teleport(player, () -> target, options, onSuccess);
    }

    /**
     * Teleportiert mit Prüfungen. Das Ziel wird erst nach der Wartezeit erneut abgefragt, damit z. B.
     * ein Teleport zu einem Spieler dessen aktuelle Position nimmt. Fehler werden dem Spieler gemeldet.
     */
    public void teleport(Player player, Supplier<@Nullable Location> target, Options options, @Nullable Runnable onSuccess) {
        Location first = target.get();
        if (first == null) {
            plugin.messages().send(player, "teleport.no-target");
            return;
        }
        if (!checkAccess(player, first)) {
            return;
        }
        String key = options.cooldownKey();
        if (cooldownsEnabled && key != null && !player.hasPermission(BYPASS_COOLDOWN)) {
            Duration left = plugin.cooldowns().remaining(player.getUniqueId(), key);
            if (!left.isZero()) {
                plugin.messages().send(player, "teleport.cooldown", "time", plugin.messages().duration(left));
                return;
            }
        }

        cancelPending(player.getUniqueId());
        if (warmupEnabled && options.warmup() && warmupSeconds > 0 && !player.hasPermission(BYPASS_WARMUP)) {
            plugin.messages().send(player, "teleport.warmup", "seconds", warmupSeconds);
            // Jede Sekunde: Countdown in der Actionbar, bei 0 teleportieren
            int[] left = {warmupSeconds};
            BukkitTask[] self = new BukkitTask[1];
            self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (left[0] > 0) {
                    if (countdownActionbar) {
                        player.sendActionBar(plugin.messages().get("teleport.countdown", "seconds", left[0]));
                    }
                    left[0]--;
                    return;
                }
                self[0].cancel();
                pending.remove(player.getUniqueId());
                if (countdownActionbar) {
                    player.sendActionBar(Component.empty());
                }
                Location now = target.get();
                if (now == null) {
                    plugin.messages().send(player, "teleport.no-target");
                } else if (player.isOnline()) {
                    execute(player, now, options, onSuccess);
                }
            }, 0L, 20L);
            // sofort merken, damit Bewegung/Schaden schon im ersten Tick abbrechen kann
            pending.put(player.getUniqueId(), self[0]);
        } else {
            execute(player, first, options, onSuccess);
        }
    }

    private void execute(Player player, Location target, Options options, @Nullable Runnable onSuccess) {
        if (!checkAccess(player, target)) {
            return;
        }
        World world = target.getWorld();
        // Chunk asynchron laden; der Future wird auf dem Hauptthread fertig
        world.getChunkAtAsync(target).thenAccept(chunk -> plugin.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            Location dest = target;
            if (options.safe() && needsSafeCheck(player)) {
                Optional<Location> safe = findSafe(target);
                if (safe.isEmpty()) {
                    plugin.messages().send(player, "teleport.unsafe");
                    return;
                }
                dest = safe.get();
            }
            player.teleportAsync(dest, TeleportCause.COMMAND).thenAccept(ok -> plugin.sync(() -> {
                if (!ok) {
                    plugin.messages().send(player, "teleport.failed");
                    return;
                }
                String key = options.cooldownKey();
                if (cooldownsEnabled && key != null) {
                    long seconds = plugin.config("teleport").getLong("cooldowns." + key, 0);
                    plugin.cooldowns().set(player.getUniqueId(), key, Duration.ofSeconds(seconds));
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }));
        }));
    }

    /** Welt-Berechtigung und Weltgrenze. */
    public boolean checkAccess(Player player, Location target) {
        World world = target.getWorld();
        if (world == null) {
            plugin.messages().send(player, "teleport.no-target");
            return false;
        }
        if (crossWorldPermission && !world.equals(player.getWorld())
                && !player.hasPermission("nucleus.teleport.world." + world.getName())) {
            plugin.messages().send(player, "teleport.world-denied", "world", world.getName());
            return false;
        }
        if (!allowOutsideBorder && !world.getWorldBorder().isInside(target) && !player.hasPermission(BYPASS_BORDER)) {
            plugin.messages().send(player, "teleport.outside-border");
            return false;
        }
        return true;
    }

    /** Sichere Position nahe dem Ziel; die exakte Position bleibt erhalten, wenn sie schon sicher ist. */
    public Optional<Location> findSafe(Location target) {
        if (!safeEnabled) {
            return Optional.of(target);
        }
        WorldBlockView view = new WorldBlockView(target.getWorld());
        return SafeLocationFinder.find(view, target.getBlockX(), target.getBlockY(), target.getBlockZ(), safeRadius, 8)
                .map(pos -> {
                    if (pos.x() == target.getBlockX() && pos.y() == target.getBlockY() && pos.z() == target.getBlockZ()) {
                        return target;
                    }
                    return new Location(target.getWorld(), pos.x() + 0.5, pos.y(), pos.z() + 0.5,
                            target.getYaw(), target.getPitch());
                });
    }

    private static boolean needsSafeCheck(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    public boolean cancelPending(UUID player) {
        BukkitTask task = pending.remove(player);
        if (task != null) {
            task.cancel();
            Player p = Bukkit.getPlayer(player);
            if (p != null && countdownActionbar) {
                p.sendActionBar(Component.empty());
            }
            return true;
        }
        return false;
    }

    /** Für Befehle, die selbst Cooldowns setzen (z. B. /tpa). */
    public boolean cooldownsEnabled() {
        return cooldownsEnabled;
    }

    // --- Abbruch der Wartezeit ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (cancelOnMove && event.hasChangedBlock() && cancelPending(event.getPlayer().getUniqueId())) {
            plugin.messages().send(event.getPlayer(), "teleport.cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (cancelOnDamage && event.getEntity() instanceof Player p && cancelPending(p.getUniqueId())) {
            plugin.messages().send(p, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelPending(event.getPlayer().getUniqueId());
        plugin.cooldowns().clear(event.getPlayer().getUniqueId());
    }
}
