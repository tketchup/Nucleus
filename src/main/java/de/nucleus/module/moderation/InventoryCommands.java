package de.nucleus.module.moderation;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.data.PlayerData;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /invsee, /enderchest, /clearinventory, /sudo, /commandspy. */
final class InventoryCommands {

    /** Spieler, die gerade ein fremdes Inventar nur ansehen dürfen (Klicks werden abgebrochen). */
    private static final Set<UUID> READ_ONLY = ConcurrentHashMap.newKeySet();

    private final NucleusPlugin plugin;
    private final ModerationModule module;
    private final CommandKit kit;

    InventoryCommands(NucleusPlugin plugin, ModerationModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    static boolean isReadOnly(UUID viewer) {
        return READ_ONLY.contains(viewer);
    }

    static void closed(UUID viewer) {
        READ_ONLY.remove(viewer);
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("invsee", "nucleus.invsee")
                .executes(kit.usage("/invsee <spieler>"))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    Player target = CommandKit.online(ctx, "player");
                    if (me.equals(target)) {
                        throw new CommandFail("moderation.invsee-self");
                    }
                    boolean modify = me.hasPermission("nucleus.invsee.modify") && !target.hasPermission("nucleus.invsee.exempt");
                    open(me, modify, () -> me.openInventory(target.getInventory()));
                    module.log().write(me, "INVSEE", target.getName(), "modify", String.valueOf(modify));
                    plugin.messages().send(me, modify ? "moderation.invsee" : "moderation.invsee-readonly", "player", target.getName());
                }))),
                "Zeigt das Inventar eines Spielers", "inv");

        CommandKit.register(r, kit.root("enderchest", "nucleus.enderchest")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    open(me, true, () -> me.openInventory(me.getEnderChest()));
                }))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.enderchest.others"))
                        .suggests(CommandKit.players()).executes(kit.run(ctx -> {
                            Player me = self(ctx);
                            Player target = CommandKit.online(ctx, "player");
                            boolean modify = me.equals(target) || me.hasPermission("nucleus.enderchest.modify");
                            open(me, modify, () -> me.openInventory(target.getEnderChest()));
                            if (!me.equals(target)) {
                                module.log().write(me, "ENDERCHEST", target.getName(), "modify", String.valueOf(modify));
                            }
                        }))),
                "Öffnet eine Endertruhe", "ec", "echest");

        CommandKit.register(r, kit.root("clearinventory", "nucleus.clearinventory")
                .executes(kit.run(ctx -> clear(sender(ctx), self(ctx))))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.clearinventory.others"))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> clear(sender(ctx), CommandKit.online(ctx, "player"))))),
                "Leert ein Inventar (inklusive Rüstung)", "ci", "clearinv");

        CommandKit.register(r, kit.root("sudo", "nucleus.sudo")
                .executes(kit.usage("/sudo <spieler> <befehl|c:nachricht>"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/sudo <spieler> <befehl|c:nachricht>"))
                        .then(greedy("command").executes(kit.run(this::sudo)))),
                "Führt einen Befehl als anderer Spieler aus");

        CommandKit.register(r, kit.root("commandspy", "nucleus.commandspy")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    PlayerData data = plugin.playerData().get(me);
                    data.commandSpy(!data.commandSpy());
                    plugin.messages().send(me, data.commandSpy() ? "moderation.commandspy-on" : "moderation.commandspy-off");
                })),
                "Zeigt dir die Befehle anderer Spieler", "cspy");
    }

    /** Erst Status setzen, dann öffnen: das Schließen des alten Fensters räumt sonst den neuen Status weg. */
    private static void open(Player viewer, boolean modify, Runnable opener) {
        READ_ONLY.remove(viewer.getUniqueId());
        opener.run();
        if (!modify) {
            READ_ONLY.add(viewer.getUniqueId());
        }
    }

    private void clear(CommandSender sender, Player target) {
        if (!sender.equals(target) && target.hasPermission("nucleus.clearinventory.exempt")) {
            throw new CommandFail("moderation.exempt", "player", target.getName());
        }
        target.getInventory().clear(); // enthält Rüstung und Zweithand
        plugin.messages().send(target, "moderation.inventory-cleared");
        if (!sender.equals(target)) {
            plugin.messages().send(sender, "moderation.inventory-cleared-other", "player", target.getName());
            module.log().write(sender, "CLEARINVENTORY", target.getName());
        }
    }

    private void sudo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        Player target = CommandKit.online(ctx, "player");
        if (target.hasPermission("nucleus.sudo.exempt")) {
            throw new CommandFail("moderation.exempt", "player", target.getName());
        }
        String input = arg(ctx, "command").trim();
        if (input.regionMatches(true, 0, "c:", 0, 2)) {
            String message = input.substring(2).trim();
            target.chat(message);
            module.log().write(sender, "SUDO", target.getName(), "chat", message);
        } else {
            String command = input.startsWith("/") ? input.substring(1) : input;
            target.performCommand(command);
            module.log().write(sender, "SUDO", target.getName(), "command", "/" + command);
        }
        plugin.messages().send(sender, "moderation.sudo", "player", target.getName(), "command", input);
    }
}
