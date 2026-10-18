package de.nucleus.module.info;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.Formats;
import de.nucleus.util.PlayerLookup;
import de.nucleus.util.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /list, /seen, /whois, /getpos, /near, /ping, /playtime, /compass, /depth, /motd, /rules, /afk. */
final class InfoCommands {


    private final NucleusPlugin plugin;
    private final InfoModule module;
    private final CommandKit kit;

    InfoCommands(NucleusPlugin plugin, InfoModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("list", "nucleus.list").executes(kit.run(this::list)),
                "Zeigt alle Spieler online, nach Welt sortiert", "who", "online");

        CommandKit.register(r, kit.root("seen", "nucleus.seen")
                .executes(kit.usage("/seen <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::seen))),
                "Zeigt, wann ein Spieler zuletzt online war", "lastseen");

        CommandKit.register(r, kit.root("whois", "nucleus.whois")
                .executes(kit.usage("/whois <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::whois))),
                "Zeigt Informationen über einen Spieler");

        CommandKit.register(r, kit.root("getpos", "nucleus.getpos")
                .executes(kit.run(ctx -> getPos(ctx, self(ctx))))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.getpos.others"))
                        .suggests(CommandKit.players()).executes(kit.run(ctx -> getPos(ctx, online(ctx, "player"))))),
                "Zeigt die aktuelle Position", "coords", "whereami");

        CommandKit.register(r, kit.root("near", "nucleus.near")
                .executes(kit.run(ctx -> near(ctx, module.settings().nearDefault())))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(kit.run(ctx -> near(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))),
                "Zeigt Spieler in der Nähe", "nearby");

        CommandKit.register(r, kit.root("ping", "nucleus.ping")
                .executes(kit.run(ctx -> ping(ctx, self(ctx))))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.ping.others"))
                        .suggests(CommandKit.players()).executes(kit.run(ctx -> ping(ctx, online(ctx, "player"))))),
                "Zeigt die Verbindungslatenz");

        CommandKit.register(r, kit.root("playtime", "nucleus.playtime")
                .executes(kit.run(ctx -> playtimeSelf(ctx)))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.playtime.others"))
                        .suggests(CommandKit.players()).executes(kit.run(this::playtimeOther))),
                "Zeigt die Spielzeit");

        CommandKit.register(r, kit.root("compass", "nucleus.compass")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    float yaw = ((me.getLocation().getYaw() % 360) + 360) % 360;
                    // Reihenfolge in der Sprachdatei: Süden, Südwesten, Westen ... (Minecraft-Yaw 0 = Süden)
                    java.util.List<String> dirs = plugin.messages().rawList("info.directions");
                    int index = Math.round(yaw / 45f) % 8;
                    String dir = index < dirs.size() ? dirs.get(index) : String.valueOf(Math.round(yaw));
                    plugin.messages().send(me, "info.compass", "direction", dir, "degrees", Math.round(yaw));
                })),
                "Zeigt deine Blickrichtung", "direction");

        CommandKit.register(r, kit.root("depth", "nucleus.depth")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    int diff = me.getLocation().getBlockY() - me.getWorld().getSeaLevel();
                    String key = diff > 0 ? "info.depth-above" : diff < 0 ? "info.depth-below" : "info.depth-sea";
                    plugin.messages().send(me, key, "blocks", Math.abs(diff));
                })),
                "Zeigt die Höhe relativ zum Meeresspiegel", "height");

        CommandKit.register(r, linesEditor(kit.root("motd", "nucleus.motd")
                        .executes(kit.run(ctx -> module.sendLines(sender(ctx), module.settings().motd()))),
                        "motd.lines", "/motd")
                        .then(CommandKit.sub("onjoin", "nucleus.motd.edit")
                                .then(word("value").suggests(CommandKit.suggest(ctx -> java.util.List.of("on", "off")))
                                        .executes(kit.run(ctx -> {
                                            boolean on = switch (arg(ctx, "value").toLowerCase(java.util.Locale.ROOT)) {
                                                case "on", "true", "an", "ja" -> true;
                                                case "off", "false", "aus", "nein" -> false;
                                                default -> throw new CommandFail("info.lines-invalid-switch");
                                            };
                                            plugin.config("info").set("motd.on-join", on);
                                            plugin.moduleConfigs().save("info");
                                            module.reloadSettings();
                                            plugin.messages().send(sender(ctx), on ? "info.motd-onjoin-on" : "info.motd-onjoin-off");
                                        })))),
                "Zeigt die Nachricht des Tages");

        CommandKit.register(r, linesEditor(kit.root("rules", "nucleus.rules")
                        .executes(kit.run(ctx -> module.sendLines(sender(ctx), module.settings().rules()))),
                        "rules", "/rules"),
                "Zeigt die Serverregeln", "regeln");

        CommandKit.register(r, kit.root("afk", "nucleus.afk")
                .executes(kit.run(ctx -> toggleAfk(self(ctx), null)))
                .then(greedy("reason").executes(kit.run(ctx -> toggleAfk(self(ctx), arg(ctx, "reason"))))),
                "Markiert dich als abwesend", "away");
    }

    /**
     * Hängt an /motd bzw. /rules die Bearbeitung an (Permission nucleus.motd.edit bzw. nucleus.rules.edit):
     * list (Rohtext mit Zeilennummern), add, set, insert, remove, clear. Gilt sofort und wird gespeichert.
     */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> linesEditor(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root, String path, String label) {
        String perm = "nucleus." + root.getLiteral() + ".edit";
        return root
                .then(CommandKit.sub("list", perm).executes(kit.run(ctx -> listLines(sender(ctx), path, label))))
                .then(CommandKit.sub("add", perm).then(greedy("text").executes(kit.run(ctx ->
                        editLines(ctx, path, label, lines -> lines.add(arg(ctx, "text")))))))
                .then(CommandKit.sub("set", perm).then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .then(greedy("text").executes(kit.run(ctx -> editLines(ctx, path, label, lines ->
                                lines.set(line(ctx, lines) - 1, arg(ctx, "text"))))))))
                .then(CommandKit.sub("insert", perm).then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .then(greedy("text").executes(kit.run(ctx -> editLines(ctx, path, label, lines ->
                                lines.add(Math.min(IntegerArgumentType.getInteger(ctx, "line"), lines.size() + 1) - 1, arg(ctx, "text"))))))))
                .then(CommandKit.sub("remove", perm).then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .executes(kit.run(ctx -> editLines(ctx, path, label, lines -> lines.remove(line(ctx, lines) - 1))))))
                .then(CommandKit.sub("clear", perm).executes(kit.run(ctx -> editLines(ctx, path, label, List::clear))));
    }

    private static int line(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        int line = IntegerArgumentType.getInteger(ctx, "line");
        if (line > lines.size()) {
            throw new CommandFail("info.lines-no-line", "line", line, "count", lines.size());
        }
        return line;
    }

    private void editLines(CommandContext<CommandSourceStack> ctx, String path, String label,
                           java.util.function.Consumer<List<String>> change) {
        List<String> lines = new ArrayList<>(plugin.config("info").getStringList(path));
        change.accept(lines);
        plugin.config("info").set(path, lines);
        plugin.moduleConfigs().save("info");
        module.reloadSettings();
        CommandSender sender = sender(ctx);
        plugin.messages().send(sender, "info.lines-saved", "command", label, "count", lines.size());
        // Vorschau, damit man das Ergebnis sofort sieht
        module.sendLines(sender, lines);
    }

    private void listLines(CommandSender sender, String path, String label) {
        List<String> lines = plugin.config("info").getStringList(path);
        if (lines.isEmpty()) {
            throw new CommandFail("info.lines-empty", "command", label);
        }
        for (int i = 0; i < lines.size(); i++) {
            // Rohtext: Tags sichtbar, damit man ihn kopieren und anpassen kann
            plugin.messages().send(sender, "info.lines-entry", "line", i + 1, "text", lines.get(i));
        }
    }

    private void toggleAfk(Player player, String reason) {
        AfkService afk = module.afk();
        afk.setAfk(player, !afk.isAfk(player), reason);
    }

    private void list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        Map<String, List<Component>> byWorld = new TreeMap<>();
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!PlayerLookup.canSee(sender, p)) {
                continue;
            }
            Component name = p.displayName();
            if (module.afk().isAfk(p)) {
                name = name.append(plugin.messages().get("afk.list-marker"));
            }
            byWorld.computeIfAbsent(p.getWorld().getName(), k -> new ArrayList<>()).add(name);
            count++;
        }
        plugin.messages().send(sender, "info.list-header", "count", count, "max", Bukkit.getMaxPlayers());
        byWorld.forEach((world, names) -> plugin.messages().send(sender, "info.list-world",
                "world", world, "count", names.size(), "players", Component.join(JoinConfiguration.commas(true), names)));
    }

    private void seen(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        Player online = target.getPlayer();
        if (online != null && PlayerLookup.canSee(sender, online)) {
            Duration since = Duration.ofMillis(System.currentTimeMillis() - module.joinTime(online.getUniqueId()));
            plugin.messages().send(sender, "info.seen-online", "player", name, "time", plugin.messages().duration(since));
            return;
        }
        plugin.playerData().withData(target.getUniqueId(), data -> {
            if (data.lastSeen() <= 0) {
                plugin.messages().send(sender, "info.seen-never", "player", name);
                return;
            }
            Duration ago = Duration.ofMillis(System.currentTimeMillis() - data.lastSeen());
            plugin.messages().send(sender, "info.seen-offline", "player", name, "time", plugin.messages().duration(ago),
                    "date", plugin.messages().date(data.lastSeen()));
        });
    }

    private void whois(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        Player online = target.getPlayer();
        boolean visible = online != null && PlayerLookup.canSee(sender, online);
        plugin.playerData().withData(target.getUniqueId(), data -> {
            var m = plugin.messages();
            m.send(sender, "whois.header", "player", name);
            m.send(sender, "whois.uuid", "uuid", target.getUniqueId().toString());
            if (visible) {
                Location l = online.getLocation();
                m.send(sender, "whois.display-name", "name", online.displayName());
                m.send(sender, "whois.status-online", "afk", module.afk().isAfk(online) ? m.get("whois.afk-yes") : m.get("whois.afk-no"));
                m.send(sender, "whois.location", "world", l.getWorld().getName(),
                        "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ());
                m.send(sender, "whois.gamemode", "gamemode",
                        m.raw("gameplay.mode." + online.getGameMode().name().toLowerCase(java.util.Locale.ROOT)));
                m.send(sender, "whois.health", "health", Formats.coord(online.getHealth()), "food", online.getFoodLevel());
                m.send(sender, "whois.flags", "fly", yesNo(online.getAllowFlight()), "op", yesNo(online.isOp()));
                m.send(sender, "whois.ping", "ping", online.getPing());
                InetSocketAddress addr = online.getAddress();
                if (addr != null && sender.hasPermission("nucleus.whois.ip")) {
                    m.send(sender, "whois.ip", "ip", addr.getAddress().getHostAddress());
                }
                m.send(sender, "whois.playtime", "time", ticksToText(online.getStatistic(Statistic.PLAY_ONE_MINUTE)));
            } else {
                m.send(sender, "whois.status-offline", "date",
                        data.lastSeen() > 0 ? plugin.messages().date(data.lastSeen()) : "?");
                m.send(sender, "whois.playtime", "time", ticksToText(data.playtimeTicks()));
            }
            if (data.firstJoin() > 0) {
                m.send(sender, "whois.first-join", "date", plugin.messages().date(data.firstJoin()));
            }
        });
    }

    private Component yesNo(boolean value) {
        return plugin.messages().get(value ? "whois.answer-yes" : "whois.answer-no");
    }

    private void getPos(CommandContext<CommandSourceStack> ctx, Player target) {
        Location l = target.getLocation();
        plugin.messages().send(sender(ctx), "info.getpos", "player", target.getName(), "world", l.getWorld().getName(),
                "x", Formats.coord(l.getX()), "y", Formats.coord(l.getY()), "z", Formats.coord(l.getZ()),
                "yaw", Math.round(l.getYaw()), "pitch", Math.round(l.getPitch()));
    }

    private void near(CommandContext<CommandSourceStack> ctx, int radius) {
        Player me = self(ctx);
        int max = module.settings().nearMax();
        if (radius > max && !me.hasPermission("nucleus.near.unlimited")) {
            throw new CommandFail("info.near-too-large", "max", max);
        }
        World world = me.getWorld();
        Location loc = me.getLocation();
        double r2 = (double) radius * radius;
        List<Player> found = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (!p.equals(me) && me.canSee(p) && p.getLocation().distanceSquared(loc) <= r2) {
                found.add(p);
            }
        }
        if (found.isEmpty()) {
            throw new CommandFail("info.near-none", "radius", radius);
        }
        found.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(loc)));
        List<Component> entries = new ArrayList<>();
        for (Player p : found) {
            entries.add(plugin.messages().get("info.near-entry", "player", p.displayName(),
                    "distance", Math.round(p.getLocation().distance(loc))));
        }
        plugin.messages().send(me, "info.near", "radius", radius,
                "players", Component.join(JoinConfiguration.commas(true), entries));
    }

    private void ping(CommandContext<CommandSourceStack> ctx, Player target) {
        plugin.messages().send(sender(ctx), "info.ping", "player", target.getName(), "ping", target.getPing());
    }

    private void playtimeSelf(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        plugin.messages().send(me, "info.playtime", "player", me.getName(),
                "time", ticksToText(me.getStatistic(Statistic.PLAY_ONE_MINUTE)));
    }

    private void playtimeOther(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        Player online = target.getPlayer();
        if (online != null) {
            plugin.messages().send(sender, "info.playtime", "player", name,
                    "time", ticksToText(online.getStatistic(Statistic.PLAY_ONE_MINUTE)));
            return;
        }
        plugin.playerData().withData(target.getUniqueId(), data ->
                plugin.messages().send(sender, "info.playtime", "player", name, "time", ticksToText(data.playtimeTicks())));
    }

    private String ticksToText(long ticks) {
        // Spielzeit auf Minuten genau reicht
        long minutes = ticks / 20 / 60;
        return minutes == 0 ? plugin.messages().raw("info.playtime-under-minute")
                : plugin.messages().duration(Duration.ofMinutes(minutes));
    }
}
