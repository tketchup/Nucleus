package de.nucleus.module.moderation;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.TimeParser;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Pattern;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /kick, /ban, /tempban, /unban, /banip, /unbanip, /baninfo, /mute, /unmute. */
final class PunishCommands {

    /** Nur IP-Literale (IPv4 oder IPv6), damit nie eine DNS-Abfrage ausgelöst wird. */
    private static final Pattern IP = Pattern.compile("[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:.]*");

    private final NucleusPlugin plugin;
    private final ModerationModule module;
    private final CommandKit kit;

    PunishCommands(NucleusPlugin plugin, ModerationModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("kick", "nucleus.kick")
                .executes(kit.usage("/kick <spieler> [grund]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> kick(ctx, null)))
                        .then(greedy("reason").executes(kit.run(ctx -> kick(ctx, arg(ctx, "reason")))))),
                "Wirft einen Spieler vom Server");

        CommandKit.register(r, kit.root("ban", "nucleus.ban")
                .executes(kit.usage("/ban <spieler> [grund]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> ban(ctx, null, null)))
                        .then(greedy("reason").executes(kit.run(ctx -> ban(ctx, null, arg(ctx, "reason")))))),
                "Bannt einen Spieler dauerhaft");

        CommandKit.register(r, kit.root("tempban", "nucleus.tempban")
                .executes(kit.usage("/tempban <spieler> <dauer> [grund]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/tempban <spieler> <dauer> [grund]"))
                        .then(word("duration").suggests(durations())
                                .executes(kit.run(ctx -> ban(ctx, duration(arg(ctx, "duration")), null)))
                                .then(greedy("reason").executes(kit.run(ctx ->
                                        ban(ctx, duration(arg(ctx, "duration")), arg(ctx, "reason"))))))),
                "Bannt einen Spieler für eine Zeit");

        CommandKit.register(r, kit.root("unban", "nucleus.unban")
                .executes(kit.usage("/unban <spieler>"))
                .then(word("player").suggests(bannedNames()).executes(kit.run(this::unban))),
                "Hebt einen Bann auf", "pardon");

        CommandKit.register(r, kit.root("banip", "nucleus.banip")
                .executes(kit.usage("/banip <spieler|ip> [grund]"))
                .then(word("target").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> banIp(ctx, null)))
                        .then(greedy("reason").executes(kit.run(ctx -> banIp(ctx, arg(ctx, "reason")))))),
                "Bannt eine IP-Adresse", "ipban");

        CommandKit.register(r, kit.root("unbanip", "nucleus.unbanip")
                .executes(kit.usage("/unbanip <ip|spieler>"))
                .then(word("target").executes(kit.run(this::unbanIp))),
                "Hebt einen IP-Bann auf", "pardonip");

        CommandKit.register(r, kit.root("baninfo", "nucleus.baninfo")
                .executes(kit.usage("/baninfo <spieler>"))
                .then(word("player").suggests(bannedNames()).executes(kit.run(this::banInfo))),
                "Zeigt Details zu einem Bann");

        // /mute <spieler> [dauer] [grund]: ist das erste Wort eine Dauer, ist es ein Zeit-Mute
        CommandKit.register(r, kit.root("mute", "nucleus.mute")
                .executes(kit.usage("/mute <spieler> [dauer] [grund]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> mute(ctx, "")))
                        .then(greedy("rest").suggests(durations()).executes(kit.run(ctx -> mute(ctx, arg(ctx, "rest")))))),
                "Schaltet einen Spieler stumm");

        CommandKit.register(r, kit.root("unmute", "nucleus.mute")
                .executes(kit.usage("/unmute <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::unmute))),
                "Hebt einen Mute auf");
    }

    // --- Kick ---

    private void kick(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        CommandSender sender = sender(ctx);
        Player target = CommandKit.online(ctx, "player");
        if (target.hasPermission("nucleus.kick.exempt") && !sender.equals(target)) {
            throw new CommandFail("moderation.exempt", "player", target.getName());
        }
        String why = reason(reason);
        target.kick(plugin.messages().get("moderation.kick-screen", "reason", why, "source", sender.getName()),
                PlayerKickEvent.Cause.PLUGIN);
        module.log().write(sender, "KICK", target.getName(), "reason", why);
        broadcastStaff(sender, "moderation.kicked", "player", target.getName(), "source", sender.getName(), "reason", why);
    }

    // --- Ban ---

    private void ban(CommandContext<CommandSourceStack> ctx, @Nullable Duration duration, @Nullable String reason) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = name(target, ctx);
        Player online = target.getPlayer();
        if (online != null && online.hasPermission("nucleus.ban.exempt")) {
            throw new CommandFail("moderation.exempt", "player", name);
        }
        String why = reason(reason);
        Instant expires = duration == null ? null : Instant.now().plus(duration);
        target.ban(why, expires, sender.getName());
        if (online != null) {
            online.kick(ModerationListener.banScreen(plugin, why, expires == null ? null : Date.from(expires), sender.getName()),
                    PlayerKickEvent.Cause.BANNED);
        }
        String time = duration == null ? plugin.messages().raw("moderation.permanent") : plugin.messages().duration(duration);
        module.log().write(sender, duration == null ? "BAN" : "TEMPBAN", name,
                "duration", duration == null ? "permanent" : TimeParser.compact(duration), "reason", why);
        broadcastStaff(sender, "moderation.banned", "player", name, "source", sender.getName(), "time", time, "reason", why);
    }

    private void unban(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String input = arg(ctx, "player");
        ProfileBanList list = Bukkit.getBanList(BanListType.PROFILE);
        PlayerProfile profile = findBannedProfile(input)
                .orElseThrow(() -> new CommandFail("moderation.not-banned", "player", input));
        list.pardon(profile);
        String name = profile.getName() != null ? profile.getName() : input;
        module.log().write(sender, "UNBAN", name);
        plugin.messages().send(sender, "moderation.unbanned", "player", name);
    }

    /** Sucht den Bann über den Namen in der Bannliste (funktioniert auch für nie gecachte Namen). */
    private Optional<PlayerProfile> findBannedProfile(String name) {
        ProfileBanList list = Bukkit.getBanList(BanListType.PROFILE);
        for (BanEntry<PlayerProfile> e : list.<BanEntry<PlayerProfile>>getEntries()) {
            PlayerProfile p = e.getBanTarget();
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private void banInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String input = arg(ctx, "player");
        PlayerProfile profile = findBannedProfile(input)
                .orElseThrow(() -> new CommandFail("moderation.not-banned", "player", input));
        BanEntry<PlayerProfile> e = Bukkit.getBanList(BanListType.PROFILE).getBanEntry(profile);
        if (e == null) {
            throw new CommandFail("moderation.not-banned", "player", input);
        }
        Date exp = e.getExpiration();
        plugin.messages().send(sender, "moderation.baninfo",
                "player", profile.getName() != null ? profile.getName() : input,
                "reason", e.getReason() == null ? "" : e.getReason(),
                "source", e.getSource() == null ? "" : e.getSource(),
                "created", plugin.messages().date(e.getCreated().getTime()),
                "expires", exp == null ? plugin.messages().raw("moderation.permanent") : plugin.messages().date(exp.getTime()));
    }

    // --- IP-Bann ---

    private void banIp(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        CommandSender sender = sender(ctx);
        String why = reason(reason);
        withAddress(sender, arg(ctx, "target"), address -> {
            IpBanList list = Bukkit.getBanList(BanListType.IP);
            list.addBan(address, why, (Instant) null, sender.getName());
            int kicked = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getAddress() != null && address.equals(p.getAddress().getAddress()) && !p.hasPermission("nucleus.ban.exempt")) {
                    p.kick(ModerationListener.banScreen(plugin, why, null, sender.getName()), PlayerKickEvent.Cause.IP_BANNED);
                    kicked++;
                }
            }
            module.log().write(sender, "BANIP", address.getHostAddress(), "reason", why, "kicked", String.valueOf(kicked));
            plugin.messages().send(sender, "moderation.ip-banned", "ip", address.getHostAddress(), "count", kicked);
        });
    }

    private void unbanIp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        withAddress(sender, arg(ctx, "target"), address -> {
            IpBanList list = Bukkit.getBanList(BanListType.IP);
            if (!list.isBanned(address)) {
                plugin.messages().send(sender, "moderation.ip-not-banned", "ip", address.getHostAddress());
                return;
            }
            list.pardon(address);
            module.log().write(sender, "UNBANIP", address.getHostAddress());
            plugin.messages().send(sender, "moderation.ip-unbanned", "ip", address.getHostAddress());
        });
    }

    /**
     * Ermittelt die Adresse aus einem IP-Literal, einem Online-Spieler oder der zuletzt gespeicherten
     * IP eines Offline-Spielers (dann asynchron aus dessen Datei; die Aktion läuft auf dem Hauptthread).
     */
    private void withAddress(CommandSender sender, String input, java.util.function.Consumer<InetAddress> action) {
        if (IP.matcher(input).matches()) {
            action.accept(literal(input));
            return;
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null && online.getAddress() != null) {
            action.accept(online.getAddress().getAddress());
            return;
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(input);
        if (known == null) {
            throw new CommandFail("moderation.invalid-ip", "ip", input);
        }
        plugin.playerData().withData(known.getUniqueId(), data -> {
            if (data.lastIp().isEmpty()) {
                plugin.messages().send(sender, "moderation.ip-unknown-offline", "player", input);
                return;
            }
            action.accept(literal(data.lastIp()));
        });
    }

    /** Nur für IP-Literale: InetAddress.getByName löst dann keine DNS-Abfrage aus. */
    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new CommandFail("moderation.invalid-ip", "ip", ip);
        }
    }

    // --- Mute ---

    private void mute(CommandContext<CommandSourceStack> ctx, String rest) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = name(target, ctx);
        Player online = target.getPlayer();
        if (online != null && online.hasPermission("nucleus.mute.exempt")) {
            throw new CommandFail("moderation.exempt", "player", name);
        }
        String trimmed = rest.trim();
        String first = trimmed.isEmpty() ? "" : trimmed.split(" ", 2)[0];
        Optional<Duration> duration = TimeParser.parse(first);
        String reason = reason(duration.isPresent() ? trimmed.substring(first.length()).trim() : trimmed);
        long until = duration.map(d -> System.currentTimeMillis() + d.toMillis()).orElse(Long.MAX_VALUE);
        String time = duration.map(plugin.messages()::duration).orElse(plugin.messages().raw("moderation.permanent"));

        plugin.playerData().withData(target.getUniqueId(), data -> {
            data.mute(until, reason);
            module.log().write(sender, "MUTE", name, "duration", duration.map(TimeParser::compact).orElse("permanent"), "reason", reason);
            plugin.messages().send(sender, "moderation.muted", "player", name, "time", time, "reason", reason);
            Player now = Bukkit.getPlayer(target.getUniqueId());
            if (now != null) {
                plugin.messages().send(now, "moderation.muted-target", "time", time, "reason", reason);
            }
        });
    }

    private void unmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = name(target, ctx);
        plugin.playerData().withData(target.getUniqueId(), data -> {
            if (!data.isMuted()) {
                plugin.messages().send(sender, "moderation.not-muted", "player", name);
                return;
            }
            data.mute(0, "");
            module.log().write(sender, "UNMUTE", name);
            plugin.messages().send(sender, "moderation.unmuted", "player", name);
            Player now = Bukkit.getPlayer(target.getUniqueId());
            if (now != null) {
                plugin.messages().send(now, "moderation.unmuted-target");
            }
        });
    }

    // --- Hilfen ---

    private String reason(@Nullable String reason) {
        return reason == null || reason.isBlank() ? plugin.messages().raw("moderation.default-reason") : reason.trim();
    }

    private static Duration duration(String input) {
        return TimeParser.parse(input).orElseThrow(() -> new CommandFail("moderation.invalid-duration", "input", input));
    }

    private static String name(OfflinePlayer p, CommandContext<CommandSourceStack> ctx) {
        return p.getName() != null ? p.getName() : arg(ctx, "player");
    }

    /** An den Ausführenden, alle mit nucleus.moderation.notify und die Konsole (jeweils einmal). */
    private void broadcastStaff(CommandSender actor, String key, Object... placeholders) {
        var msg = plugin.messages().get(key, placeholders);
        java.util.Set<CommandSender> receivers = new java.util.LinkedHashSet<>();
        receivers.add(actor);
        receivers.add(Bukkit.getConsoleSender());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("nucleus.moderation.notify")) {
                receivers.add(p);
            }
        }
        receivers.forEach(r -> r.sendMessage(msg));
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> durations() {
        return CommandKit.suggest(ctx -> java.util.List.of("30m", "1h", "12h", "1d", "7d", "30d"));
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> bannedNames() {
        return CommandKit.suggest(ctx -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            ProfileBanList list = Bukkit.getBanList(BanListType.PROFILE);
            for (BanEntry<PlayerProfile> e : list.<BanEntry<PlayerProfile>>getEntries()) {
                if (e.getBanTarget().getName() != null) {
                    names.add(e.getBanTarget().getName());
                }
            }
            return names;
        });
    }
}
