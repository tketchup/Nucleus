package de.nucleus.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.core.TeleportService;
import de.nucleus.core.TeleportService.Options;
import de.nucleus.data.PlayerData;
import de.nucleus.util.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.word;

/** /tpa, /tpahere, /tpaccept, /tpdeny, /tpcancel, /tptoggle. */
final class TpaCommands {

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    private final CommandKit kit;

    TpaCommands(NucleusPlugin plugin, TeleportModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("tpa", "nucleus.tpa")
                .executes(kit.usage("/tpa <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> request(ctx, false)))),
                "Bittet einen Spieler, dich zu ihm teleportieren zu dürfen", "call", "tpask");

        CommandKit.register(r, kit.root("tpahere", "nucleus.tpahere")
                .executes(kit.usage("/tpahere <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> request(ctx, true)))),
                "Bittet einen Spieler, sich zu dir zu teleportieren");

        CommandKit.register(r, kit.root("tpaccept", "nucleus.tpaccept")
                .executes(kit.run(ctx -> accept(ctx, null)))
                .then(word("player").suggests(requesters()).executes(kit.run(ctx -> accept(ctx, online(ctx, "player"))))),
                "Nimmt eine Teleport-Anfrage an", "tpyes");

        CommandKit.register(r, kit.root("tpdeny", "nucleus.tpdeny")
                .executes(kit.run(ctx -> deny(ctx, null)))
                .then(word("player").suggests(requesters()).executes(kit.run(ctx -> deny(ctx, online(ctx, "player"))))),
                "Lehnt eine Teleport-Anfrage ab", "tpno");

        CommandKit.register(r, kit.root("tpcancel", "nucleus.tpcancel")
                .executes(kit.run(ctx -> cancel(ctx, null)))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> cancel(ctx, online(ctx, "player"))))),
                "Zieht deine Teleport-Anfragen zurück");

        CommandKit.register(r, kit.root("tptoggle", "nucleus.tptoggle")
                .executes(kit.run(ctx -> {
                    Player player = self(ctx);
                    PlayerData data = plugin.playerData().get(player);
                    data.teleportRequests(!data.teleportRequests());
                    plugin.messages().send(player, data.teleportRequests() ? "tpa.toggle-on" : "tpa.toggle-off");
                })),
                "Schaltet Teleport-Anfragen an dich an oder aus");
    }

    private void request(CommandContext<CommandSourceStack> ctx, boolean here) {
        Player from = self(ctx);
        Player to = online(ctx, "player");
        if (from.equals(to)) {
            throw new CommandFail("tpa.self");
        }
        if (!plugin.playerData().get(to).teleportRequests() && !from.hasPermission("nucleus.tptoggle.bypass")) {
            throw new CommandFail("tpa.disabled", "player", to.getName());
        }
        // Cooldown gilt für das Senden von Anfragen
        if (plugin.teleports().cooldownsEnabled() && !from.hasPermission(TeleportService.BYPASS_COOLDOWN)) {
            Duration left = plugin.cooldowns().remaining(from.getUniqueId(), "tpa");
            if (!left.isZero()) {
                throw new CommandFail("teleport.cooldown", "time", plugin.messages().duration(left));
            }
            plugin.cooldowns().set(from.getUniqueId(), "tpa",
                    Duration.ofSeconds(plugin.config("teleport").getLong("cooldowns.tpa")));
        }
        module.tpa().send(from, to, here);

        Component accept = plugin.messages().get("tpa.button-accept")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()))
                .hoverEvent(plugin.messages().get("tpa.button-accept-hover"));
        Component deny = plugin.messages().get("tpa.button-deny")
                .clickEvent(ClickEvent.runCommand("/tpdeny " + from.getName()))
                .hoverEvent(plugin.messages().get("tpa.button-deny-hover"));
        int seconds = module.settings().tpaExpireSeconds();
        plugin.messages().send(to, here ? "tpa.received-here" : "tpa.received",
                "player", from.getName(), "seconds", seconds, "accept", accept, "deny", deny);
        plugin.messages().send(from, "tpa.sent", "player", to.getName(), "seconds", seconds);
    }

    private void accept(CommandContext<CommandSourceStack> ctx, @Nullable Player from) {
        Player me = self(ctx);
        TpaManager.Request req = module.tpa().take(me.getUniqueId(), from == null ? null : from.getUniqueId());
        if (req == null) {
            throw new CommandFail("tpa.none");
        }
        Player requester = Bukkit.getPlayer(req.from());
        if (requester == null) {
            throw new CommandFail("tpa.none");
        }
        plugin.messages().send(me, "tpa.accepted-target", "player", requester.getName());
        plugin.messages().send(requester, "tpa.accepted-sender", "player", me.getName());
        // Wer sich bewegt, bekommt die Wartezeit. Ziel wird erst nach der Wartezeit bestimmt.
        Player traveller = req.here() ? me : requester;
        Player destination = req.here() ? requester : me;
        UUID destId = destination.getUniqueId();
        plugin.teleports().teleport(traveller, () -> {
            Player d = Bukkit.getPlayer(destId);
            return d == null ? null : d.getLocation();
        }, new Options(null, true, false), () -> plugin.messages().send(traveller, "tpa.teleported", "player", destination.getName()));
    }

    private void deny(CommandContext<CommandSourceStack> ctx, @Nullable Player from) {
        Player me = self(ctx);
        TpaManager.Request req = module.tpa().take(me.getUniqueId(), from == null ? null : from.getUniqueId());
        if (req == null) {
            throw new CommandFail("tpa.none");
        }
        Player requester = Bukkit.getPlayer(req.from());
        plugin.messages().send(me, "tpa.denied-target", "player", requester != null ? requester.getName() : "?");
        if (requester != null) {
            plugin.messages().send(requester, "tpa.denied-sender", "player", me.getName());
        }
    }

    private void cancel(CommandContext<CommandSourceStack> ctx, @Nullable Player to) {
        Player me = self(ctx);
        List<TpaManager.Request> removed = module.tpa().cancelOutgoing(me.getUniqueId(), to == null ? null : to.getUniqueId());
        if (removed.isEmpty()) {
            throw new CommandFail("tpa.none-outgoing");
        }
        for (TpaManager.Request r : removed) {
            Player target = Bukkit.getPlayer(r.to());
            if (target != null) {
                plugin.messages().send(target, "tpa.cancelled-target", "player", me.getName());
            }
        }
        plugin.messages().send(me, "tpa.cancelled", "count", removed.size());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> requesters() {
        return CommandKit.suggest(ctx -> ctx.getSource().getSender() instanceof Player p
                ? module.tpa().requesterNames(p.getUniqueId()) : List.of());
    }
}
