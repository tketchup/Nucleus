package de.nucleus.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gespeicherte Daten eines Spielers. Wird nur auf dem Hauptthread verändert; zum Speichern erzeugt
 * {@link #serialize()} auf dem Hauptthread einen Text, den der IO-Thread dann schreibt.
 */
public final class PlayerData {

    private final UUID uuid;
    private String lastName = "";
    private long firstJoin;
    private long lastSeen;
    private final Map<String, NamedLocation> homes = new LinkedHashMap<>();
    /** Symbol pro Home (Item-Schlüssel wie "minecraft:red_bed"), nur wenn eins gewählt wurde. */
    private final Map<String, String> homeIcons = new HashMap<>();
    private @Nullable NamedLocation backLocation;
    private boolean teleportRequests = true;
    /** Thread-sicher, weil der Chat (asynchron) darin nachschaut. */
    private final Set<UUID> ignored = ConcurrentHashMap.newKeySet();
    private boolean socialSpy;
    private @Nullable String nick;
    private final List<Mail> mails = new ArrayList<>();
    private long playtimeTicks;
    /** Mute bis (Epoch-Millis), 0 = nicht gemutet, Long.MAX_VALUE = dauerhaft. volatile: Chat liest asynchron. */
    private volatile long muteUntil;
    private volatile String muteReason = "";
    private boolean frozen;
    private @Nullable String jail;
    /** Jail bis (Epoch-Millis), Long.MAX_VALUE = dauerhaft. */
    private long jailUntil;
    private @Nullable NamedLocation jailReturn;
    private boolean vanished;
    private boolean commandSpy;
    private String lastIp = "";
    private boolean god;
    private boolean fly;
    /** Powertools: Item-Schlüssel (z. B. "minecraft:blaze_rod") → Befehl ohne "/". */
    private final Map<String, String> powertools = new HashMap<>();

    private boolean dirty;

    /** Eine Mail im Postfach. */
    public record Mail(String from, long time, String text, boolean read) {
    }

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public String lastName() {
        return lastName;
    }

    public void lastName(String name) {
        if (!name.equals(lastName)) {
            lastName = name;
            markDirty();
        }
    }

    public long firstJoin() {
        return firstJoin;
    }

    public void firstJoin(long millis) {
        firstJoin = millis;
        markDirty();
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long millis) {
        lastSeen = millis;
        markDirty();
    }

    // --- Homes (Schlüssel immer kleingeschrieben) ---

    public Map<String, NamedLocation> homes() {
        return Collections.unmodifiableMap(homes);
    }

    public @Nullable NamedLocation home(String name) {
        return homes.get(name.toLowerCase(Locale.ROOT));
    }

    public void setHome(String name, NamedLocation loc) {
        homes.put(name.toLowerCase(Locale.ROOT), loc);
        markDirty();
    }

    public boolean removeHome(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean removed = homes.remove(key) != null;
        if (removed) {
            homeIcons.remove(key);
            markDirty();
        }
        return removed;
    }

    /** Benennt ein Home um (Position und Symbol bleiben). @return false, wenn alt fehlt oder neu belegt ist */
    public boolean renameHome(String oldName, String newName) {
        String from = oldName.toLowerCase(Locale.ROOT);
        String to = newName.toLowerCase(Locale.ROOT);
        if (!homes.containsKey(from) || (homes.containsKey(to) && !from.equals(to))) {
            return false;
        }
        // Reihenfolge erhalten: neu aufbauen
        Map<String, NamedLocation> copy = new LinkedHashMap<>();
        homes.forEach((k, v) -> copy.put(k.equals(from) ? to : k, v));
        homes.clear();
        homes.putAll(copy);
        String icon = homeIcons.remove(from);
        if (icon != null) {
            homeIcons.put(to, icon);
        }
        markDirty();
        return true;
    }

    public @Nullable String homeIcon(String name) {
        return homeIcons.get(name.toLowerCase(Locale.ROOT));
    }

    public void homeIcon(String name, String itemKey) {
        homeIcons.put(name.toLowerCase(Locale.ROOT), itemKey);
        markDirty();
    }

    // --- Teleport ---

    public @Nullable NamedLocation backLocation() {
        return backLocation;
    }

    public void backLocation(@Nullable NamedLocation loc) {
        backLocation = loc;
        markDirty();
    }

    public boolean teleportRequests() {
        return teleportRequests;
    }

    public void teleportRequests(boolean enabled) {
        teleportRequests = enabled;
        markDirty();
    }

    // --- Chat ---

    public Set<UUID> ignored() {
        return Collections.unmodifiableSet(ignored);
    }

    public boolean isIgnoring(UUID other) {
        return ignored.contains(other);
    }

    public void addIgnored(UUID other) {
        if (ignored.add(other)) {
            markDirty();
        }
    }

    /** @return true, wenn der Spieler jetzt ignoriert wird */
    public boolean toggleIgnore(UUID other) {
        boolean nowIgnored = ignored.add(other) || !ignored.remove(other);
        markDirty();
        return nowIgnored;
    }

    public boolean socialSpy() {
        return socialSpy;
    }

    public void socialSpy(boolean enabled) {
        socialSpy = enabled;
        markDirty();
    }

    /** Nickname als MiniMessage-Text, null = kein Nick. */
    public @Nullable String nick() {
        return nick;
    }

    public void nick(@Nullable String miniMessage) {
        nick = miniMessage;
        markDirty();
    }

    public List<Mail> mails() {
        return Collections.unmodifiableList(mails);
    }

    public void addMail(Mail mail, int maxMails) {
        mails.add(mail);
        while (mails.size() > maxMails) {
            mails.removeFirst(); // älteste zuerst verwerfen
        }
        markDirty();
    }

    public long unreadMails() {
        return mails.stream().filter(m -> !m.read()).count();
    }

    public void markMailsRead() {
        mails.replaceAll(m -> m.read() ? m : new Mail(m.from(), m.time(), m.text(), true));
        markDirty();
    }

    public void clearMails() {
        mails.clear();
        markDirty();
    }

    // --- Statistik ---

    /** Spielzeit in Ticks, beim Verlassen aus der Vanilla-Statistik übernommen (für Offline-Abfragen). */
    public long playtimeTicks() {
        return playtimeTicks;
    }

    public void playtimeTicks(long ticks) {
        playtimeTicks = ticks;
        markDirty();
    }

    // --- Moderation ---

    /** Auch asynchron aufrufbar. Abgelaufene Mutes zählen nicht. */
    public boolean isMuted() {
        long until = muteUntil;
        return until != 0 && until > System.currentTimeMillis();
    }

    public long muteUntil() {
        return muteUntil;
    }

    public String muteReason() {
        return muteReason;
    }

    public void mute(long until, String reason) {
        muteUntil = until;
        muteReason = reason;
        markDirty();
    }

    public boolean frozen() {
        return frozen;
    }

    public void frozen(boolean value) {
        frozen = value;
        markDirty();
    }

    public @Nullable String jail() {
        return jail;
    }

    public long jailUntil() {
        return jailUntil;
    }

    public @Nullable NamedLocation jailReturn() {
        return jailReturn;
    }

    public void jail(@Nullable String name, long until, @Nullable NamedLocation returnTo) {
        jail = name;
        jailUntil = until;
        jailReturn = returnTo;
        markDirty();
    }

    public boolean vanished() {
        return vanished;
    }

    public void vanished(boolean value) {
        vanished = value;
        markDirty();
    }

    public boolean commandSpy() {
        return commandSpy;
    }

    public void commandSpy(boolean value) {
        commandSpy = value;
        markDirty();
    }

    /** Letzte bekannte IP-Adresse (für /banip bei Offline-Spielern). */
    public String lastIp() {
        return lastIp;
    }

    public void lastIp(String ip) {
        if (!ip.equals(lastIp)) {
            lastIp = ip;
            markDirty();
        }
    }

    // --- Gameplay ---

    public boolean god() {
        return god;
    }

    public void god(boolean value) {
        god = value;
        markDirty();
    }

    public boolean fly() {
        return fly;
    }

    public void fly(boolean value) {
        fly = value;
        markDirty();
    }

    public Map<String, String> powertools() {
        return Collections.unmodifiableMap(powertools);
    }

    public void powertool(String itemKey, @Nullable String command) {
        if (command == null) {
            powertools.remove(itemKey);
        } else {
            powertools.put(itemKey, command);
        }
        markDirty();
    }

    public void clearPowertools() {
        powertools.clear();
        markDirty();
    }

    // --- Persistenz ---

    public void markDirty() {
        dirty = true;
    }

    boolean dirty() {
        return dirty;
    }

    void clearDirty() {
        dirty = false;
    }

    /** Hauptthread: aktuellen Stand als YAML-Text festhalten. */
    String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", lastName);
        yaml.set("first-join", firstJoin);
        yaml.set("last-seen", lastSeen);
        ConfigurationSection homeSec = yaml.createSection("homes");
        homes.forEach((name, loc) -> {
            ConfigurationSection sec = homeSec.createSection(name);
            loc.write(sec);
            if (homeIcons.containsKey(name)) {
                sec.set("icon", homeIcons.get(name));
            }
        });
        if (backLocation != null) {
            backLocation.write(yaml.createSection("back"));
        }
        yaml.set("teleport-requests", teleportRequests);
        yaml.set("ignored", ignored.stream().map(UUID::toString).toList());
        yaml.set("social-spy", socialSpy);
        yaml.set("nick", nick);
        List<Map<String, Object>> mailList = new ArrayList<>();
        for (Mail m : mails) {
            Map<String, Object> map = new HashMap<>();
            map.put("from", m.from());
            map.put("time", m.time());
            map.put("text", m.text());
            map.put("read", m.read());
            mailList.add(map);
        }
        yaml.set("mails", mailList);
        yaml.set("playtime-ticks", playtimeTicks);
        if (muteUntil != 0) {
            yaml.set("mute.until", muteUntil);
            yaml.set("mute.reason", muteReason);
        }
        yaml.set("frozen", frozen);
        if (jail != null) {
            yaml.set("jail.name", jail);
            yaml.set("jail.until", jailUntil);
            if (jailReturn != null) {
                jailReturn.write(yaml.createSection("jail.return"));
            }
        }
        yaml.set("vanished", vanished);
        yaml.set("command-spy", commandSpy);
        yaml.set("last-ip", lastIp);
        yaml.set("god", god);
        yaml.set("fly", fly);
        ConfigurationSection pt = yaml.createSection("powertools");
        powertools.forEach(pt::set);
        return yaml.saveToString();
    }

    /** IO-Thread: aus YAML-Text lesen. Reine Datenverarbeitung ohne Server-Zugriff. */
    static PlayerData deserialize(UUID uuid, YamlConfiguration yaml) {
        PlayerData d = new PlayerData(uuid);
        d.lastName = yaml.getString("name", "");
        d.firstJoin = yaml.getLong("first-join");
        d.lastSeen = yaml.getLong("last-seen");
        ConfigurationSection homeSec = yaml.getConfigurationSection("homes");
        if (homeSec != null) {
            for (String key : homeSec.getKeys(false)) {
                ConfigurationSection sec = homeSec.getConfigurationSection(key);
                NamedLocation loc = NamedLocation.read(sec);
                if (loc != null) {
                    String lower = key.toLowerCase(Locale.ROOT);
                    d.homes.put(lower, loc);
                    if (sec.isString("icon")) {
                        d.homeIcons.put(lower, sec.getString("icon"));
                    }
                }
            }
        }
        d.backLocation = NamedLocation.read(yaml.getConfigurationSection("back"));
        d.teleportRequests = yaml.getBoolean("teleport-requests", true);
        for (String id : yaml.getStringList("ignored")) {
            try {
                d.ignored.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignoredEntry) {
                // kaputter Eintrag
            }
        }
        d.socialSpy = yaml.getBoolean("social-spy");
        d.nick = yaml.getString("nick");
        for (Map<?, ?> map : yaml.getMapList("mails")) {
            d.mails.add(new Mail(String.valueOf(map.get("from")),
                    map.get("time") instanceof Number n ? n.longValue() : 0L,
                    String.valueOf(map.get("text")),
                    Boolean.TRUE.equals(map.get("read"))));
        }
        d.playtimeTicks = yaml.getLong("playtime-ticks");
        d.muteUntil = yaml.getLong("mute.until");
        d.muteReason = yaml.getString("mute.reason", "");
        d.frozen = yaml.getBoolean("frozen");
        d.jail = yaml.getString("jail.name");
        d.jailUntil = yaml.getLong("jail.until");
        d.jailReturn = NamedLocation.read(yaml.getConfigurationSection("jail.return"));
        d.vanished = yaml.getBoolean("vanished");
        d.commandSpy = yaml.getBoolean("command-spy");
        d.lastIp = yaml.getString("last-ip", "");
        d.god = yaml.getBoolean("god");
        d.fly = yaml.getBoolean("fly");
        ConfigurationSection pt = yaml.getConfigurationSection("powertools");
        if (pt != null) {
            for (String key : pt.getKeys(false)) {
                d.powertools.put(key, pt.getString(key, ""));
            }
        }
        return d;
    }
}
