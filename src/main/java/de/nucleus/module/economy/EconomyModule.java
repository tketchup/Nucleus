package de.nucleus.module.economy;

import de.nucleus.NucleusPlugin;
import de.nucleus.api.EconomyService;
import de.nucleus.command.CommandKit;
import de.nucleus.core.Module;
import de.nucleus.core.ModuleScope;
import de.nucleus.data.YamlFile;
import de.nucleus.util.Configs;
import de.nucleus.util.Money;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Modul "economy": Konten, /balance, /pay, /baltop, /eco, /paytoggle und die öffentliche
 * {@link EconomyService}-API (im Bukkit-ServicesManager registriert, solange das Modul aktiv ist).
 *
 * <p>Konten liegen in economy.yml und komplett im Speicher. Gespeichert wird asynchron, sobald sich
 * etwas geändert hat (alle paar Sekunden), beim Abschalten und beim Stoppen.
 */
public final class EconomyModule implements Module, Listener {

    record Settings(String singular, String plural, String symbol, String format,
                    long startBalance, long maxBalance, long payMin, long payMax,
                    int pageSize, long cacheMillis, long autosaveTicks) {
    }

    private final NucleusPlugin plugin;
    private final ModuleScope scope;
    private final YamlFile file;
    /** Bleibt über /nucleus reload erhalten, damit nie ein halb geladener Stand gespeichert wird. */
    private final Ledger ledger = new Ledger(Long.MAX_VALUE);
    private final EconomyServiceImpl service;

    /** Nachkommastellen: beim ersten Laden festgelegt, Änderungen gelten nach einem Neustart. */
    private int decimals = -1;
    private volatile boolean loaded;
    private volatile Settings settings;
    /** Nur gesetzt, wenn VaultUnlocked installiert ist (Klasse wird sonst nie geladen). */
    private VaultHook vaultHook;

    public EconomyModule(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.scope = new ModuleScope(plugin);
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve("economy.yml"));
        this.service = new EconomyServiceImpl(this, ledger);
    }

    @Override
    public void enable() {
        FileConfiguration c = plugin.config("economy");
        int configured = Math.clamp(c.getInt("decimals"), 0, 4);
        if (decimals < 0) {
            decimals = configured;
        } else if (decimals != configured) {
            plugin.getLogger().warning("economy.decimals wurde geändert, das gilt erst nach einem Neustart.");
        }
        settings = loadSettings(c);
        ledger.maxBalance(settings.maxBalance());

        scope.listen(this);
        scope.timer(this::saveIfDirty, settings.autosaveTicks(), settings.autosaveTicks());
        Bukkit.getServicesManager().register(EconomyService.class, service, plugin, ServicePriority.Normal);
        if (c.getBoolean("vault.enabled") && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            vaultHook = VaultHook.registerIfPresent(plugin, this);
        }
        if (!loaded) {
            loadFile();
        }
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(EconomyService.class, service);
        if (vaultHook != null) {
            vaultHook.unregister();
            vaultHook = null;
        }
        scope.close();
        saveIfDirty();
    }

    @Override
    public void registerCommands(Commands registrar) {
        new EconomyCommands(plugin, this, new CommandKit(plugin, "economy")).register(registrar);
    }

    private Settings loadSettings(FileConfiguration c) {
        return new Settings(
                Configs.string(c, "currency-singular"),
                Configs.string(c, "currency-plural"),
                Configs.string(c, "symbol"),
                Configs.string(c, "format"),
                money(c, "starting-balance"),
                Math.max(1, money(c, "max-balance")),
                Math.max(1, money(c, "pay.min-amount")),
                money(c, "pay.max-amount"),
                Math.max(1, c.getInt("baltop.page-size")),
                Math.max(0, c.getLong("baltop.cache-seconds")) * 1000L,
                Math.max(1, c.getLong("autosave-seconds")) * 20L);
    }

    /**
     * Betrag aus der Config, z. B. "100" oder "0.01". Wird mit 4 Nachkommastellen gelesen und auf die
     * eingestellten abgerundet (0.01 bei decimals 0 ergibt 0). Ungültig = 0 mit Warnung.
     */
    private long money(FileConfiguration c, String path) {
        String raw = Configs.string(c, path);
        return Money.parse(raw, 4).stream().map(v -> Money.rescale(v, 4, decimals)).findFirst().orElseGet(() -> {
            plugin.getLogger().warning("Ungültiger Betrag in config.yml bei " + path + ": '" + raw + "'");
            return 0L;
        });
    }

    // --- Laden/Speichern ---

    private void loadFile() {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                // Lieber ohne Economy als mit leeren Konten, die beim Speichern die Datei überschreiben
                plugin.getLogger().log(Level.SEVERE, "economy.yml konnte nicht gelesen werden, Economy bleibt gesperrt", error);
                return;
            }
            int fileDecimals = yaml.getInt("decimals", decimals);
            Map<UUID, Ledger.Account> accounts = new HashMap<>();
            ConfigurationSection sec = yaml.getConfigurationSection("accounts");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection a = sec.getConfigurationSection(key);
                    try {
                        UUID id = UUID.fromString(key);
                        long balance = Money.rescale(a.getLong("balance"), fileDecimals, decimals);
                        Ledger.Account account = new Ledger.Account(a.getString("name", "?"), balance);
                        account.acceptsPayments = a.getBoolean("accepts-payments", true);
                        accounts.put(id, account);
                    } catch (IllegalArgumentException | NullPointerException e) {
                        plugin.getLogger().warning("Ungültiges Konto in economy.yml übersprungen: " + key);
                    }
                }
            }
            if (fileDecimals != decimals) {
                plugin.getLogger().warning("Nachkommastellen geändert (" + fileDecimals + " → " + decimals
                        + "), alle Kontostände wurden umgerechnet.");
            }
            ledger.load(accounts);
            loaded = true;
            if (fileDecimals != decimals) {
                saveNow();
            }
            Bukkit.getOnlinePlayers().forEach(this::ensureAccount);
            plugin.getLogger().info("Economy: " + accounts.size() + " Konten geladen.");
        }));
    }

    private void saveIfDirty() {
        if (loaded && ledger.dirty()) {
            saveNow();
        }
    }

    /** Momentaufnahme jetzt, YAML erzeugen und schreiben im IO-Thread. */
    private void saveNow() {
        Map<UUID, Ledger.Account> snapshot = ledger.snapshotAndClean();
        int dec = decimals;
        plugin.io().write(plugin.getDataFolder().toPath().resolve("economy.yml"), () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(java.util.List.of(
                    "Nucleus Economy. Beträge in der kleinsten Einheit (bei decimals: 2 also Cent).",
                    "Nicht bei laufendem Server bearbeiten."));
            yaml.set("decimals", dec);
            ConfigurationSection sec = yaml.createSection("accounts");
            snapshot.forEach((id, a) -> {
                ConfigurationSection s = sec.createSection(id.toString());
                s.set("name", a.name);
                s.set("balance", a.balance);
                s.set("accepts-payments", a.acceptsPayments);
            });
            return yaml.saveToString();
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (loaded) {
            ensureAccount(event.getPlayer());
        }
    }

    private void ensureAccount(Player player) {
        ledger.ensure(player.getUniqueId(), player.getName(), settings.startBalance());
    }

    // --- Zugriff für Befehle und Service ---

    boolean loaded() {
        return loaded;
    }

    int decimals() {
        return decimals;
    }

    Settings settings() {
        return settings;
    }

    Ledger ledger() {
        return ledger;
    }

    EconomyServiceImpl service() {
        return service;
    }
}
