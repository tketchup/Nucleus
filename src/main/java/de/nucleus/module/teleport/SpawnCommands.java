package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.core.TeleportService.Options;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /setspawn und /spawn [spieler]. */
final class SpawnCommands {

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    private final CommandKit kit;

    SpawnCommands(NucleusPlugin plugin, TeleportModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("setspawn", "nucleus.setspawn")
                .executes(kit.run(ctx -> {
                    Player player = self(ctx);
                    module.spawns().set(player.getLocation());
                    plugin.messages().send(player, module.settings().spawnPerWorld() ? "spawn.set-world" : "spawn.set",
                            "world", player.getWorld().getName());
                })),
                "Setzt den Spawn an deiner Position");

        CommandKit.register(r, kit.root("spawn", "nucleus.spawn")
                .executes(kit.run(ctx -> {
                    Player player = self(ctx);
                    plugin.teleports().teleport(player, module.spawns().spawnFor(player.getWorld()),
                            Options.player("spawn"), () -> plugin.messages().send(player, "spawn.teleported"));
                }))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.spawn.others"))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> {
                            CommandSender sender = sender(ctx);
                            Player target = online(ctx, "player");
                            plugin.teleports().teleport(target, module.spawns().spawnFor(target.getWorld()),
                                    Options.admin(), () -> {
                                        plugin.messages().send(target, "spawn.teleported");
                                        plugin.messages().send(sender, "spawn.teleported-other", "player", target.getName());
                                    });
                        }))),
                "Teleportiert zum Spawn");
    }
}
