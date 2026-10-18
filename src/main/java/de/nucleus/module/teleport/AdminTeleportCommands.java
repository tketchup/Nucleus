package de.nucleus.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.core.TeleportService.Options;
import de.nucleus.data.NamedLocation;
import de.nucleus.util.Formats;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.math.FinePosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/**
 * /tp, /tphere, /tpall, /tppos, /top, /back, /tpo. Diese Befehle überschreiben die Vanilla-Befehle
 * nur, solange das Modul aktiv ist; /minecraft:tp bleibt immer erreichbar.
 */
final class AdminTeleportCommands {

    private static final String TOGGLE_BYPASS = "nucleus.tptoggle.bypass";

    private final NucleusPlugin plugin;
    private final CommandKit kit;

    AdminTeleportCommands(NucleusPlugin plugin, TeleportModule module, CommandKit kit) {
        this.plugin = plugin;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("tp", "nucleus.tp")
                .executes(kit.usage("/tp <spieler> [ziel]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> tpSelf(ctx, false)))
                        .then(word("target").requires(src -> src.getSender().hasPermission("nucleus.tp.others"))
                                .suggests(CommandKit.players())
                                .executes(kit.run(ctx -> tpOther(ctx, false))))),
                "Teleportiert dich oder einen Spieler zu einem Spieler", "teleport");

        CommandKit.register(r, kit.root("tpo", "nucleus.tpo")
                .executes(kit.usage("/tpo <spieler> [ziel]"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> tpSelf(ctx, true)))
                        .then(word("target").suggests(CommandKit.players())
                                .executes(kit.run(ctx -> tpOther(ctx, true))))),
                "Teleport ohne Rücksicht auf /tptoggle");

        CommandKit.register(r, kit.root("tphere", "nucleus.tphere")
                .executes(kit.usage("/tphere <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::tpHere))),
                "Holt einen Spieler zu dir", "s");

        CommandKit.register(r, kit.root("tpall", "nucleus.tpall")
                .executes(kit.run(ctx -> tpAll(ctx, self(ctx))))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> tpAll(ctx, online(ctx, "player"))))),
                "Holt alle Spieler zu dir oder zu einem Spieler");

        CommandKit.register(r, kit.root("tppos", "nucleus.tppos")
                .executes(kit.usage("/tppos <x> <y> <z> [welt]"))
                .then(Commands.argument("pos", ArgumentTypes.finePosition(true))
                        .executes(kit.run(ctx -> tpPos(ctx, null)))
                        .then(CommandKit.worldArg("world")
                                .executes(kit.run(ctx -> tpPos(ctx, CommandKit.world(ctx, "world")))))),
                "Teleportiert zu Koordinaten", "tpposition");

        CommandKit.register(r, kit.root("top", "nucleus.top")
                .executes(kit.run(this::top)),
                "Teleportiert auf den höchsten Block über dir");

        CommandKit.register(r, kit.root("back", "nucleus.back")
                .executes(kit.run(this::back)),
                "Teleportiert zur letzten Position (vor Teleport oder Tod)", "return");
    }

    /** /tp &lt;spieler&gt;: sich selbst zu einem Spieler. */
    private void tpSelf(CommandContext<CommandSourceStack> ctx, boolean override) {
        Player me = self(ctx);
        Player target = online(ctx, "player");
        if (me.equals(target)) {
            throw new CommandFail("tp.self");
        }
        checkToggle(me, target, override);
        plugin.teleports().teleport(me, target.getLocation(), Options.exact(),
                () -> plugin.messages().send(me, "tp.teleported", "player", target.getName()));
    }

    /** /tp &lt;spieler&gt; &lt;ziel&gt;: einen Spieler zu einem anderen. Auch von der Konsole. */
    private void tpOther(CommandContext<CommandSourceStack> ctx, boolean override) {
        CommandSender sender = sender(ctx);
        Player who = online(ctx, "player");
        Player target = online(ctx, "target");
        if (who.equals(target)) {
            throw new CommandFail("tp.self");
        }
        checkToggle(sender, target, override);
        plugin.teleports().teleport(who, target.getLocation(), Options.exact(), () -> {
            plugin.messages().send(who, "tp.teleported", "player", target.getName());
            plugin.messages().send(sender, "tp.teleported-other", "player", who.getName(), "target", target.getName());
        });
    }

    private void tpHere(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        Player target = online(ctx, "player");
        if (me.equals(target)) {
            throw new CommandFail("tp.self");
        }
        checkToggle(me, target, false);
        plugin.teleports().teleport(target, me.getLocation(), Options.exact(), () -> {
            plugin.messages().send(target, "tp.teleported", "player", me.getName());
            plugin.messages().send(me, "tp.here", "player", target.getName());
        });
    }

    private void tpAll(CommandContext<CommandSourceStack> ctx, Player destination) {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(destination)) {
                plugin.teleports().teleport(p, destination.getLocation(), Options.exact(),
                        () -> plugin.messages().send(p, "tp.teleported", "player", destination.getName()));
                count++;
            }
        }
        plugin.messages().send(sender(ctx), "tp.all", "count", count, "player", destination.getName());
    }

    private void tpPos(CommandContext<CommandSourceStack> ctx, World world) throws CommandSyntaxException {
        Player me = self(ctx);
        FinePosition pos = ctx.getArgument("pos", FinePositionResolver.class).resolve(ctx.getSource());
        World w = world != null ? world : me.getWorld();
        Location target = new Location(w, pos.x(), pos.y(), pos.z(), me.getYaw(), me.getPitch());
        plugin.teleports().teleport(me, target, Options.admin(), () -> plugin.messages().send(me, "tp.position",
                "x", Formats.coord(target.getX()), "y", Formats.coord(target.getY()), "z", Formats.coord(target.getZ()),
                "world", w.getName()));
    }

    private void top(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        Location loc = me.getLocation();
        int y = me.getWorld().getHighestBlockYAt(loc) + 1;
        if (y <= loc.getBlockY()) {
            throw new CommandFail("tp.top-already");
        }
        Location target = new Location(loc.getWorld(), loc.getBlockX() + 0.5, y, loc.getBlockZ() + 0.5, loc.getYaw(), loc.getPitch());
        plugin.teleports().teleport(me, target, Options.player("top"), () -> plugin.messages().send(me, "tp.top"));
    }

    private void back(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        NamedLocation back = plugin.playerData().get(me).backLocation();
        if (back == null) {
            throw new CommandFail("back.none");
        }
        Location target = back.toLocation();
        if (target == null) {
            throw new CommandFail("teleport.world-missing", "world", back.world());
        }
        plugin.teleports().teleport(me, target, Options.player("back"), () -> plugin.messages().send(me, "back.teleported"));
    }

    /** Respektiert /tptoggle des Ziels, außer bei /tpo oder mit Bypass-Permission. */
    private void checkToggle(CommandSender sender, Player target, boolean override) {
        if (override || sender.hasPermission(TOGGLE_BYPASS)) {
            return;
        }
        if (!plugin.playerData().get(target).teleportRequests()) {
            throw new CommandFail("tpa.disabled", "player", target.getName());
        }
    }
}
