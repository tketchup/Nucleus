package de.nucleus.data;

import de.nucleus.NucleusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Spielerdaten pro UUID als YAML-Datei in {@code plugins/Nucleus/playerdata/}.
 *
 * <ul>
 *   <li>Online-Spieler liegen im Speicher. Geladen wird beim Login im asynchronen Pre-Login-Thread.</li>
 *   <li>Gespeichert wird regelmäßig (nur geänderte Daten), beim Verlassen und beim Stoppen.</li>
 *   <li>Alle Dateizugriffe laufen über den einen IO-Thread ({@link AsyncIO}), der Hauptthread wartet nie.</li>
 * </ul>
 */
public final class PlayerDataStore {

    private final NucleusPlugin plugin;
    private final Path folder;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();
    private @Nullable BukkitTask autosaveTask;

    public PlayerDataStore(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("playerdata");
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(new PlayerDataListener(this), plugin);
        // Spieler, die schon online sind (z. B. Plugin nachträglich geladen): einmalig laden
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.computeIfAbsent(p.getUniqueId(), id -> plugin.io().supply(() -> loadFile(id)).join());
        }
        restartAutosave();
    }

    /** Liest das Speicherintervall neu aus der Config. */
    public void restartAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        long ticks = Math.max(30, plugin.getConfig().getLong("data.autosave-seconds")) * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, ticks, ticks);
    }

    /** Daten eines Online-Spielers. Nie null, solange der Spieler online ist. */
    public PlayerData get(Player player) {
        PlayerData data = online.get(player.getUniqueId());
        if (data == null) {
            // Sollte nicht passieren (Login lädt vor). Notfall: leerer Datensatz, der nichts überschreibt,
            // bis der echte geladen ist.
            plugin.getLogger().warning("Spielerdaten von " + player.getName() + " fehlten, lade nach.");
            data = plugin.io().supply(() -> loadFile(player.getUniqueId())).join();
            online.put(player.getUniqueId(), data);
        }
        return data;
    }

    public @Nullable PlayerData getIfLoaded(UUID uuid) {
        return online.get(uuid);
    }

    /**
     * Lädt Daten eines (evtl. offline) Spielers. Der Callback läuft auf dem Hauptthread. Ist der Spieler
     * online, wird direkt der Datensatz im Speicher benutzt.
     */
    public java.util.concurrent.CompletableFuture<Void> withData(UUID uuid, Consumer<PlayerData> action) {
        PlayerData cached = online.get(uuid);
        if (cached != null) {
            action.accept(cached);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        plugin.io().supply(() -> loadFile(uuid)).thenAccept(loaded -> plugin.sync(() -> {
            try {
                // Spieler könnte in der Zwischenzeit beigetreten sein
                PlayerData target = online.getOrDefault(uuid, loaded);
                action.accept(target);
                if (target == loaded && loaded.dirty()) {
                    save(loaded);
                }
            } finally {
                done.complete(null);
            }
        }));
        return done;
    }

    // --- Lebenszyklus, aufgerufen vom PlayerDataListener ---

    /** Pre-Login-Thread (asynchron): blockiert nur diesen Thread, bis die Datei gelesen ist. */
    void loadForLogin(UUID uuid) {
        if (online.containsKey(uuid)) {
            return; // alte Sitzung noch online, deren Daten nicht überschreiben
        }
        online.put(uuid, plugin.io().supply(() -> loadFile(uuid)).join());
    }

    void handleJoin(Player player) {
        PlayerData data = get(player);
        long now = System.currentTimeMillis();
        if (data.firstJoin() == 0) {
            data.firstJoin(now);
        }
        data.lastName(player.getName());
        data.lastSeen(now);
        if (player.getAddress() != null) {
            data.lastIp(player.getAddress().getAddress().getHostAddress());
        }
    }

    void handleQuit(Player player) {
        PlayerData data = online.remove(player.getUniqueId());
        if (data != null) {
            data.lastSeen(System.currentTimeMillis());
            // Für /playtime und /whois bei Offline-Spielern, ohne deren Vanilla-Datei lesen zu müssen
            data.playtimeTicks(player.getStatistic(Statistic.PLAY_ONE_MINUTE));
            save(data);
        }
    }

    /** Hauptthread: alle geänderten Online-Datensätze in die Warteschlange zum Schreiben. */
    public void saveDirty() {
        for (PlayerData data : online.values()) {
            if (data.dirty()) {
                save(data);
            }
        }
    }

    /** Beim Stoppen: alles Offene einreihen; AsyncIO#shutdown wartet danach auf das Schreiben. */
    public void shutdown() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        saveDirty();
        online.clear();
    }

    private void save(PlayerData data) {
        String text = data.serialize();
        data.clearDirty();
        plugin.io().write(file(data.uuid()), text);
    }

    /** IO-Thread. Kaputte Dateien werden beiseitegelegt statt überschrieben. */
    private PlayerData loadFile(UUID uuid) {
        Path file = file(uuid);
        if (!Files.exists(file)) {
            return new PlayerData(uuid);
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file));
            return PlayerData.deserialize(uuid, yaml);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Spielerdatei " + file + " ist beschädigt, wird als .broken gesichert", e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nichts mehr zu retten
            }
            return new PlayerData(uuid);
        }
    }

    private Path file(UUID uuid) {
        return folder.resolve(uuid + ".yml");
    }

    /** Für das spätere Durchsuchen aller Spielerdateien (z. B. Baltop). */
    public Path folder() {
        return folder;
    }
}
