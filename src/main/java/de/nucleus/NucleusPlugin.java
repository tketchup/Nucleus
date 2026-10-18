package de.nucleus;

import de.nucleus.command.CoreCommands;
import de.nucleus.core.Messages;
import de.nucleus.core.ModuleManager;
import de.nucleus.core.TeleportService;
import de.nucleus.data.AsyncIO;
import de.nucleus.data.PlayerDataStore;
import de.nucleus.util.ConfigUpdater;
import de.nucleus.util.CooldownManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Einstiegspunkt von Nucleus. Startet den Kern (IO, Nachrichten, Spielerdaten, Teleport-Dienst)
 * und danach die in der config.yml aktivierten Module.
 */
public final class NucleusPlugin extends JavaPlugin {

    /** Einträge früherer Versionen, die beim Update aus der config.yml entfernt werden. */
    private static final java.util.List<String> OBSOLETE_CONFIG = java.util.List.of(
            "chat.motd-on-join", "chat.motd", "chat.rules"); // seit MOTD/Regeln im info-Modul liegen

    private AsyncIO io;
    private Messages messages;
    private PlayerDataStore playerData;
    private CooldownManager cooldowns;
    private TeleportService teleports;
    private ModuleManager modules;
    private de.nucleus.core.ModuleConfigs moduleConfigs;
    private de.nucleus.importer.EssentialsImporter essentialsImporter;

    @Override
    public void onEnable() {
        io = new AsyncIO(getLogger());
        saveDefaultConfig();
        updateConfigFile();
        moduleConfigs = new de.nucleus.core.ModuleConfigs(this);
        moduleConfigs.migrateFromMainConfig();
        messages = new Messages(this);
        messages.load();
        de.nucleus.command.CommandKit.descriptions(messages);
        cooldowns = new CooldownManager();
        playerData = new PlayerDataStore(this);
        playerData.start();
        teleports = new TeleportService(this);
        teleports.start();
        // Schutz für alle Nucleus-Menüs (GUI), immer aktiv
        Bukkit.getPluginManager().registerEvents(new de.nucleus.gui.MenuListener(), this);

        modules = new ModuleManager(this);
        modules.enableConfigured();

        // Befehle nur über Brigadier. Der Handler läuft beim Start und bei jedem /minecraft:reload
        // und meldet nur die Befehle der gerade aktiven Module an.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            new CoreCommands(this).register(event.registrar());
            modules.registerCommands(event.registrar());
        });

        essentialsImporter = new de.nucleus.importer.EssentialsImporter(this);
        // Hinweis, wenn alte EssentialsX-Daten herumliegen und noch nicht übernommen wurden
        if (essentialsImporter.defaultFolder().isDirectory()
                && !new java.io.File(getDataFolder(), de.nucleus.importer.EssentialsImporter.MARKER).exists()) {
            getLogger().info("EssentialsX-Daten gefunden. Übernehmen mit: /nucleus import essentials"
                    + (getServer().getOnlineMode() ? "" : " (Daten von einem Online-Server: /nucleus import essentials offline)"));
        }

        getLogger().info("Nucleus " + getPluginMeta().getVersion() + " aktiviert.");
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (teleports != null) {
            teleports.stop();
        }
        if (playerData != null) {
            playerData.shutdown();
        }
        if (io != null) {
            io.shutdown(); // wartet, bis alles geschrieben ist
        }
    }

    /**
     * /nucleus reload: Config, Nachrichten und Module neu laden.
     *
     * @return true, wenn sich die Menge der Module mit Befehlen geändert hat
     */
    public boolean reloadAll() {
        reloadConfig();
        updateConfigFile();
        moduleConfigs.reloadAll();
        messages.load();
        teleports.reload();
        playerData.restartAutosave();
        boolean changed = modules.reload();
        // Clients bekommen den Befehlsbaum neu, damit requires-Prüfungen (Permission, Modul aktiv) greifen
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        return changed;
    }

    /**
     * Ergänzt in plugins/Nucleus/config.yml neue Einträge aus der mitgelieferten Version (mit Kommentaren),
     * z. B. neue Module nach einem Update. Geschrieben wird asynchron.
     */
    private void updateConfigFile() {
        FileConfiguration config = getConfig();
        if (config.getDefaults() == null) {
            return;
        }
        int added = ConfigUpdater.addMissing(config, config.getDefaults());
        int removed = 0;
        for (String path : OBSOLETE_CONFIG) {
            if (config.contains(path, true)) {
                config.set(path, null);
                removed++;
            }
        }
        if (added > 0 || removed > 0) {
            io.write(getDataFolder().toPath().resolve("config.yml"), config.saveToString());
            getLogger().info("config.yml aktualisiert: " + added + " neue, " + removed + " veraltete Einträge entfernt.");
        }
    }

    /**
     * Schreibt die aktuelle config.yml (mit Kommentaren) asynchron, z. B. nach Änderungen im Spiel
     * wie /motd set. Lange MiniMessage-Zeilen werden dabei nicht umbrochen.
     */
    public void saveConfigAsync() {
        if (getConfig() instanceof org.bukkit.configuration.file.YamlConfiguration yaml) {
            yaml.options().width(1000);
        }
        io.write(getDataFolder().toPath().resolve("config.yml"), getConfig().saveToString());
    }

    /** Führt Code auf dem Hauptthread aus (sofort, wenn schon dort). */
    public void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    public AsyncIO io() {
        return io;
    }

    public Messages messages() {
        return messages;
    }

    public PlayerDataStore playerData() {
        return playerData;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public TeleportService teleports() {
        return teleports;
    }

    public ModuleManager modules() {
        return modules;
    }

    /** Einstellungen eines Moduls aus modules/&lt;modul&gt;.yml. */
    public org.bukkit.configuration.file.FileConfiguration config(String module) {
        return moduleConfigs.get(module);
    }

    public de.nucleus.importer.EssentialsImporter essentialsImporter() {
        return essentialsImporter;
    }

    public de.nucleus.core.ModuleConfigs moduleConfigs() {
        return moduleConfigs;
    }
}
