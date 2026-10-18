package de.nucleus.importer;

import de.nucleus.NucleusPlugin;
import de.nucleus.api.EconomyService;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.PlayerData;
import de.nucleus.module.chat.ChatModule;
import de.nucleus.module.moderation.ModerationModule;
import de.nucleus.module.teleport.TeleportModule;
import de.nucleus.util.LegacyCodes;
import de.nucleus.util.Money;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Übernimmt Daten aus einem EssentialsX-Ordner: Homes, Warps, Spawn, Jails, Kontostände, Nicknames,
 * /back-Position, Ignorierlisten, Mutes, SocialSpy, TPA-Toggle, "zuletzt online".
 *
 * <p>Regeln: Es wird nur ergänzt, nie überschrieben. Gleichnamige Homes/Warps/Jails in Nucleus bleiben,
 * ein vorhandener Spawn bleibt, Kontostände werden nur übernommen, solange das Nucleus-Konto noch
 * fehlt oder auf dem Startguthaben steht. Man kann den Import also gefahrlos wiederholen.
 *
 * <p>Mit {@code offline} werden die UUIDs über den Spielernamen in Offline-UUIDs umgerechnet: für Daten
 * von einem Online-Server, wenn Nucleus mit online-mode=false läuft. Spieler ohne gespeicherten Namen
 * lassen sich dann nicht zuordnen und werden übersprungen.
 *
 * <p>Gelesen wird im IO-Thread (sicheres YAML-Laden ohne Objekt-Erzeugung), angewendet auf dem Hauptthread.
 */
public final class EssentialsImporter {

    /** Alles, was aus dem Essentials-Ordner gelesen wurde. */
    private record Snapshot(List<EssentialsData.User> users, Map<String, EssentialsData.Loc> warps,
                            Map<String, EssentialsData.Loc> jails, Map<String, EssentialsData.Loc> spawns,
                            int brokenFiles, int unmapped,
                            @Nullable List<String> motd, @Nullable List<String> rules) {
    }

    /** Zähler für den Abschlussbericht. */
    private static final class Report {
        final AtomicInteger players = new AtomicInteger();
        final AtomicInteger homes = new AtomicInteger();
        final AtomicInteger homesSkipped = new AtomicInteger();
        final AtomicInteger balances = new AtomicInteger();
        final AtomicInteger nicks = new AtomicInteger();
        final AtomicInteger mutes = new AtomicInteger();
        int warps;
        int jails;
        String texts = "";
        int spawns;
        int skipped;
    }

    public static final String MARKER = ".essentials-imported";

    private final NucleusPlugin plugin;
    private boolean running;

    public EssentialsImporter(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    /** Standardordner: plugins/Essentials neben plugins/Nucleus. */
    public File defaultFolder() {
        return new File(plugin.getDataFolder().getParentFile(), "Essentials");
    }

    public void run(CommandSender sender, File folder, boolean offlineUuids) {
        run(sender, folder, offlineUuids, false);
    }

    /**
     * @param textsOnly nur MOTD und Regeln übernehmen, dabei vorhandene überschreiben
     *                  (/nucleus import essentials motd). Die alten Zeilen landen vorher im Log.
     */
    public void run(CommandSender sender, File folder, boolean offlineUuids, boolean textsOnly) {
        if (running) {
            plugin.messages().send(sender, "core.import-running");
            return;
        }
        if (!new File(folder, "userdata").isDirectory() && !new File(folder, "warps").isDirectory()
                && !new File(folder, "motd.txt").isFile()) {
            plugin.messages().send(sender, "core.import-no-folder", "folder", folder.getPath());
            return;
        }
        running = true;
        plugin.messages().send(sender, "core.import-started", "folder", folder.getPath());
        if (offlineUuids) {
            plugin.messages().send(sender, "core.import-offline");
        }
        plugin.io().supply(() -> read(folder, offlineUuids)).whenComplete((snapshot, error) -> plugin.sync(() -> {
            if (error != null) {
                running = false;
                plugin.getLogger().log(Level.SEVERE, "EssentialsX-Import fehlgeschlagen", error);
                plugin.messages().send(sender, "error.internal");
                return;
            }
            if (textsOnly) {
                running = false;
                String result = importTexts(snapshot, true);
                plugin.messages().send(sender, "core.import-texts", "result",
                        result.isEmpty() ? plugin.messages().raw("core.import-texts-none") : result);
            } else {
                apply(sender, snapshot);
            }
        }));
    }

    // --- Lesen (IO-Thread) ---

    private Snapshot read(File folder, boolean offlineUuids) {
        // SafeConstructor: nur Maps, Listen und einfache Werte, keine Java-Objekte aus fremden Dateien
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        int[] broken = {0};
        List<EssentialsData.User> users = new ArrayList<>();
        File[] userFiles = new File(folder, "userdata").listFiles((dir, name) -> name.endsWith(".yml"));
        if (userFiles != null) {
            for (File f : userFiles) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                } catch (IllegalArgumentException e) {
                    continue; // sehr alte Dateien nach Spielernamen: ohne UUID nicht sicher zuzuordnen
                }
                Map<?, ?> map = load(yaml, f, broken);
                if (map != null) {
                    users.add(EssentialsData.parseUser(uuid, map));
                }
            }
        }
        Map<String, EssentialsData.Loc> warps = new LinkedHashMap<>();
        File[] warpFiles = new File(folder, "warps").listFiles((dir, name) -> name.endsWith(".yml"));
        if (warpFiles != null) {
            for (File f : warpFiles) {
                Map<?, ?> map = load(yaml, f, broken);
                if (map != null) {
                    var warp = EssentialsData.parseWarp(f.getName().substring(0, f.getName().length() - 4), map);
                    if (warp != null) {
                        warps.put(warp.getKey(), warp.getValue());
                    }
                }
            }
        }
        int unmapped = 0;
        if (offlineUuids) {
            // Erst die Zuordnung alt → neu für alle, damit auch Ignorierlisten umgerechnet werden können
            Map<UUID, UUID> mapping = new java.util.HashMap<>();
            for (EssentialsData.User u : users) {
                if (u.name() != null) {
                    mapping.put(u.uuid(), EssentialsData.offlineUuid(u.name()));
                }
            }
            List<EssentialsData.User> mapped = new ArrayList<>();
            for (EssentialsData.User u : users) {
                UUID target = mapping.get(u.uuid());
                if (target == null) {
                    unmapped++;
                } else {
                    mapped.add(EssentialsData.withUuid(u, target, mapping));
                }
            }
            users = mapped;
        }
        List<String> motd = readLines(new File(folder, "motd.txt"));
        List<String> rules = readLines(new File(folder, "rules.txt"));
        Map<?, ?> jailFile = load(yaml, new File(folder, "jail.yml"), broken);
        Map<?, ?> spawnFile = load(yaml, new File(folder, "spawn.yml"), broken);
        return new Snapshot(users, warps,
                EssentialsData.parseLocationSection(jailFile == null ? null : jailFile.get("jails")),
                EssentialsData.parseLocationSection(spawnFile == null ? null : spawnFile.get("spawns")),
                broken[0], unmapped, motd, rules);
    }

    /** Textdatei (motd.txt, rules.txt) als umgewandelte MiniMessage-Zeilen; null wenn nicht vorhanden. */
    private @Nullable List<String> readLines(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                out.add(EssentialsData.convertText(line));
            }
            while (!out.isEmpty() && out.getLast().isBlank()) {
                out.removeLast();
            }
            return out;
        } catch (IOException e) {
            plugin.getLogger().warning("Nicht lesbar: " + file + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * MOTD und Regeln nach modules/info.yml. Ohne force nur, wenn dort noch der mitgelieferte Standard
     * steht (eigene Texte werden nie überschrieben). Mit force werden die alten Zeilen ins Log geschrieben.
     *
     * @return kurze Beschreibung für den Bericht, leer wenn nichts übernommen wurde
     */
    private String importTexts(Snapshot data, boolean force) {
        var info = plugin.config("info");
        List<String> done = new ArrayList<>();
        for (String[] t : new String[][]{{"motd.lines", "MOTD"}, {"rules", "Regeln"}}) {
            List<String> lines = t[0].equals("rules") ? data.rules() : data.motd();
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<String> current = info.getStringList(t[0]);
            boolean isDefault = info.getDefaults() != null && current.equals(info.getDefaults().getStringList(t[0]));
            if (!force && !isDefault) {
                continue;
            }
            if (!current.isEmpty() && !isDefault) {
                plugin.getLogger().info("Bisherige " + t[1] + " vor dem Import (zum Wiederherstellen): " + current);
            }
            info.set(t[0], lines);
            done.add(t[1] + " (" + lines.size() + " Zeilen)");
        }
        if (!done.isEmpty()) {
            plugin.moduleConfigs().save("info");
            // Info-Modul sofort neu einlesen, damit die neue MOTD direkt gilt
            plugin.modules().reloadSettings("info");
        }
        return String.join(", ", done);
    }

    private @Nullable Map<?, ?> load(Yaml yaml, File file, int[] broken) {
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            return loaded instanceof Map<?, ?> map ? map : Map.of();
        } catch (IOException | RuntimeException e) {
            broken[0]++;
            plugin.getLogger().warning("EssentialsX-Datei nicht lesbar, übersprungen: " + file + " (" + e.getMessage() + ")");
            return null;
        }
    }

    // --- Anwenden (Hauptthread) ---

    private void apply(CommandSender sender, Snapshot data) {
        Report report = new Report();
        report.skipped += data.unmapped();
        TeleportModule teleport = plugin.modules().get("teleport", TeleportModule.class);
        ModerationModule moderation = plugin.modules().get("moderation", ModerationModule.class);

        if (teleport != null) {
            data.warps().forEach((rawName, loc) -> {
                String name = EssentialsData.cleanName(rawName);
                if (name == null || teleport.warps().get(name) != null) {
                    report.skipped++;
                } else {
                    teleport.warps().set(name, named(loc));
                    report.warps++;
                }
            });
            EssentialsData.Loc spawn = data.spawns().getOrDefault("default",
                    data.spawns().values().stream().findFirst().orElse(null));
            Location spawnLoc = spawn == null ? null : named(spawn).toLocation();
            if (spawnLoc != null && teleport.spawns().isEmpty()) {
                teleport.spawns().set(spawnLoc);
                report.spawns++;
            }
        }
        if (moderation != null) {
            data.jails().forEach((rawName, loc) -> {
                String name = EssentialsData.cleanName(rawName);
                if (name == null || moderation.jails().get(name) != null) {
                    report.skipped++;
                } else {
                    moderation.jails().set(name, named(loc));
                    report.jails++;
                }
            });
        }

        report.texts = importTexts(data, false);
        EconomyService eco = plugin.modules().isActive("economy")
                ? Bukkit.getServicesManager().load(EconomyService.class) : null;
        ChatModule chat = plugin.modules().get("chat", ChatModule.class);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (EssentialsData.User user : data.users()) {
            importMoney(eco, user, report);
            pending.add(plugin.playerData().withData(user.uuid(), d -> mergeUser(d, user, chat, report)));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ok, error) -> plugin.sync(() -> {
            running = false;
            plugin.io().write(new File(plugin.getDataFolder(), MARKER).toPath(), "EssentialsX-Import am " + java.time.LocalDateTime.now() + "\n");
            plugin.messages().send(sender, "core.import-done",
                    "players", report.players.get(), "homes", report.homes.get(), "homes_skipped", report.homesSkipped.get(),
                    "warps", report.warps, "jails", report.jails, "spawns", report.spawns,
                    "balances", report.balances.get(), "nicks", report.nicks.get(), "mutes", report.mutes.get(),
                    "skipped", report.skipped, "broken", data.brokenFiles());
            if (!report.texts.isEmpty()) {
                plugin.messages().send(sender, "core.import-texts", "result", report.texts);
            } else if (data.motd() != null) {
                plugin.messages().send(sender, "core.import-texts-kept");
            }
            plugin.getLogger().info("EssentialsX-Import abgeschlossen: " + report.players.get() + " Spieler, "
                    + report.homes.get() + " Homes, " + report.warps + " Warps, " + report.balances.get() + " Kontostände.");
        }));
    }

    /** Kontostand nur, wenn Nucleus noch kein eigenes Guthaben für den Spieler hat. */
    private void importMoney(@Nullable EconomyService eco, EssentialsData.User user, Report report) {
        // 0 bringt nichts: dann behält der Spieler einfach das Nucleus-Startguthaben
        if (eco == null || user.money() == null || user.money().signum() == 0) {
            return;
        }
        if (eco.hasAccount(user.uuid()) && eco.balance(user.uuid()) != eco.startingBalance()) {
            return;
        }
        OptionalLong amount = Money.fromDecimal(user.money(), eco.decimals(), RoundingMode.DOWN);
        if (amount.isEmpty()) {
            return;
        }
        eco.ensureAccount(user.uuid(), user.name() == null ? "?" : user.name());
        if (eco.set(user.uuid(), amount.getAsLong()).success()) {
            report.balances.incrementAndGet();
        }
    }

    private void mergeUser(PlayerData d, EssentialsData.User u, @Nullable ChatModule chat, Report report) {
        report.players.incrementAndGet();
        if (d.lastName().isEmpty() && u.name() != null) {
            d.lastName(u.name());
        }
        if (d.lastSeen() == 0 && u.lastLogout() > 0) {
            d.lastSeen(u.lastLogout());
        }
        u.homes().forEach((rawName, loc) -> {
            String name = EssentialsData.cleanName(rawName);
            if (name == null || d.home(name) != null) {
                report.homesSkipped.incrementAndGet();
            } else {
                d.setHome(name, named(loc));
                report.homes.incrementAndGet();
            }
        });
        if (d.backLocation() == null && u.lastLocation() != null) {
            d.backLocation(named(u.lastLocation()));
        }
        u.ignored().forEach(d::addIgnored);
        if (u.socialSpy() && !d.socialSpy()) {
            d.socialSpy(true);
        }
        if (!u.teleportEnabled() && d.teleportRequests()) {
            d.teleportRequests(false);
        }
        long now = System.currentTimeMillis();
        if (u.muted() && !d.isMuted()) {
            // EssentialsX: 0 = dauerhaft
            long until = u.muteUntil() <= 0 ? Long.MAX_VALUE : u.muteUntil();
            if (until > now) {
                d.mute(until, u.muteReason() == null ? "" : u.muteReason());
                report.mutes.incrementAndGet();
            }
        }
        if (d.nick() == null && u.nickname() != null) {
            // &-Farbcodes übernehmen, MiniMessage-Zeichen aus dem alten Nick aber nicht als Tags deuten
            String mini = LegacyCodes.toMiniMessage(MiniMessage.miniMessage().escapeTags(u.nickname()));
            d.nick(mini);
            if (chat != null) {
                chat.indexNick(u.uuid(), PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(mini)));
            }
            report.nicks.incrementAndGet();
        }
    }

    /** EssentialsX speichert die Welt als UUID plus Namen; Nucleus speichert den Namen. */
    private static NamedLocation named(EssentialsData.Loc loc) {
        String world = loc.worldName();
        if (world.isEmpty()) {
            try {
                World w = Bukkit.getWorld(UUID.fromString(loc.world()));
                world = w != null ? w.getName() : loc.world();
            } catch (IllegalArgumentException e) {
                world = loc.world(); // ältere Versionen: Weltname statt UUID
            }
        }
        return new NamedLocation(world, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
    }
}
