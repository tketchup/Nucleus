package de.nucleus.module.moderation;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.data.NamedLocation;
import de.nucleus.util.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /freeze, /jail, /unjail, /setjail, /deljail, /jails, /vanish. */
final class ControlCommands {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final NucleusPlugin plugin;
    private final ModerationModule module;
    private final CommandKit kit;

    ControlCommands(NucleusPlugin plugin, ModerationModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("freeze", "nucleus.freeze")
                .executes(kit.usage("/freeze <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::freeze))),
                "Friert einen Spieler ein (oder taut ihn auf)", "unfreeze");

        CommandKit.register(r, kit.root("jail", "nucleus.jail")
                .executes(kit.usage("/jail <spieler> <jail> [dauer] [grund]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/jail <spieler> <jail> [dauer] [grund]"))
                        .then(word("jail").suggests(jailNames())
                                .executes(kit.run(ctx -> jail(ctx, "")))
                                .then(greedy("rest").executes(kit.run(ctx -> jail(ctx, arg(ctx, "rest"))))))),
                "Sperrt einen Spieler ins Jail");

        CommandKit.register(r, kit.root("unjail", "nucleus.jail")
                .executes(kit.usage("/unjail <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::unjail))),
                "Holt einen Spieler aus dem Jail");

        CommandKit.register(r, kit.root("setjail", "nucleus.setjail")
                .executes(kit.usage("/setjail <name>"))
                .then(word("name").executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    String name = arg(ctx, "name");
                    if (!NAME.matcher(name).matches()) {
                        throw new CommandFail("moderation.jail-invalid-name");
                    }
                    boolean isNew = module.jails().set(name, NamedLocation.of(me.getLocation()));
                    plugin.messages().send(me, isNew ? "moderation.jail-set" : "moderation.jail-updated", "name", name);
                }))),
                "Setzt ein Jail an deiner Position", "createjail");

        CommandKit.register(r, kit.root("deljail", "nucleus.setjail")
                .executes(kit.usage("/deljail <name>"))
                .then(word("name").suggests(jailNames()).executes(kit.run(ctx -> {
                    String name = arg(ctx, "name");
                    if (!module.jails().remove(name)) {
                        throw new CommandFail("moderation.jail-unknown", "name", name);
                    }
                    plugin.messages().send(sender(ctx), "moderation.jail-deleted", "name", name);
                }))),
                "Löscht ein Jail", "removejail");

        CommandKit.register(r, kit.root("jails", "nucleus.jail")
                .executes(kit.run(ctx -> {
                    if (module.jails().all().isEmpty()) {
                        throw new CommandFail("moderation.jail-none");
                    }
                    plugin.messages().send(sender(ctx), "moderation.jail-list",
                            "jails", String.join(", ", module.jails().all().keySet()));
                })),
                "Listet alle Jails auf");

        CommandKit.register(r, kit.root("vanish", "nucleus.vanish")
                .executes(kit.run(ctx -> toggleVanish(sender(ctx), self(ctx))))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.vanish.others"))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> toggleVanish(sender(ctx), CommandKit.online(ctx, "player"))))),
                "Macht dich unsichtbar", "v");
    }

    private void freeze(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        Player target = CommandKit.online(ctx, "player");
        if (target.hasPermission("nucleus.freeze.exempt")) {
            throw new CommandFail("moderation.exempt", "player", target.getName());
        }
        var data = plugin.playerData().get(target);
        boolean now = !data.frozen();
        data.frozen(now);
        module.log().write(sender, now ? "FREEZE" : "UNFREEZE", target.getName());
        plugin.messages().send(sender, now ? "moderation.frozen" : "moderation.unfrozen", "player", target.getName());
        plugin.messages().send(target, now ? "moderation.frozen-target" : "moderation.unfrozen-target");
    }

    private void jail(CommandContext<CommandSourceStack> ctx, String rest) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        String jailName = arg(ctx, "jail");
        if (module.jails().get(jailName) == null) {
            throw new CommandFail("moderation.jail-unknown", "name", jailName);
        }
        Player online = target.getPlayer();
        if (online != null && online.hasPermission("nucleus.jail.exempt")) {
            throw new CommandFail("moderation.exempt", "player", name);
        }
        String trimmed = rest.trim();
        String first = trimmed.isEmpty() ? "" : trimmed.split(" ", 2)[0];
        Optional<Duration> duration = TimeParser.parse(first);
        String reasonText = duration.isPresent() ? trimmed.substring(first.length()).trim() : trimmed;
        String reason = reasonText.isEmpty() ? plugin.messages().raw("moderation.default-reason") : reasonText;
        long until = duration.map(d -> System.currentTimeMillis() + d.toMillis()).orElse(Long.MAX_VALUE);
        String time = duration.map(plugin.messages()::duration).orElse(plugin.messages().raw("moderation.permanent"));

        plugin.playerData().withData(target.getUniqueId(), data -> {
            Player now = Bukkit.getPlayer(target.getUniqueId());
            // Rückkehrpunkt: aktuelle Position, beim erneuten Einsperren den alten behalten
            NamedLocation back = data.jail() != null ? data.jailReturn()
                    : now != null ? NamedLocation.of(now.getLocation()) : null;
            data.jail(jailName.toLowerCase(java.util.Locale.ROOT), until, back);
            if (now != null) {
                module.jailService().sendToJail(now, data);
                plugin.messages().send(now, "moderation.jailed-target", "time", time, "reason", reason);
            }
            module.log().write(sender, "JAIL", name, "jail", jailName,
                    "duration", duration.map(TimeParser::compact).orElse("permanent"), "reason", reason);
            plugin.messages().send(sender, "moderation.jailed", "player", name, "jail", jailName, "time", time);
        });
    }

    private void unjail(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        plugin.playerData().withData(target.getUniqueId(), data -> {
            if (data.jail() == null) {
                plugin.messages().send(sender, "moderation.not-jailed", "player", name);
                return;
            }
            Player now = Bukkit.getPlayer(target.getUniqueId());
            if (now != null) {
                module.jailService().release(now, data, false);
            } else {
                // Offline: beim nächsten Beitritt ist die Zeit "abgelaufen" und der Spieler wird zurückgesetzt
                data.jail(data.jail(), 0, data.jailReturn());
            }
            module.log().write(sender, "UNJAIL", name);
            plugin.messages().send(sender, "moderation.unjailed", "player", name);
        });
    }

    private void toggleVanish(CommandSender sender, Player target) {
        VanishService vanish = module.vanish();
        boolean now = !vanish.isVanished(target);
        vanish.set(target, now, true);
        plugin.messages().send(target, now ? "moderation.vanish-on" : "moderation.vanish-off");
        if (!sender.equals(target)) {
            plugin.messages().send(sender, now ? "moderation.vanish-on-other" : "moderation.vanish-off-other",
                    "player", target.getName());
        }
        module.log().write(sender, now ? "VANISH" : "UNVANISH", target.getName());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> jailNames() {
        return CommandKit.suggest(ctx -> module.jails().all().keySet());
    }
}
