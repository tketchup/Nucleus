package de.nucleus.module.chat;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import de.nucleus.data.PlayerData;
import de.nucleus.util.UserText;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.sender;

/** /me, /broadcast. */
final class AnnounceCommands {

    private final NucleusPlugin plugin;
    private final ChatModule module;
    private final CommandKit kit;

    AnnounceCommands(NucleusPlugin plugin, ChatModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("me", "nucleus.me")
                .executes(kit.usage("/me <aktion>"))
                .then(greedy("action").executes(kit.run(ctx -> {
                    CommandSender sender = sender(ctx);
                    Component name = sender instanceof Player p ? p.displayName() : plugin.messages().get("msg.console-name");
                    Component line = plugin.messages().parse(module.settings().meFormat(),
                            "display_name", name, "player", sender.getName(),
                            "message", UserText.parse(arg(ctx, "action"), sender, "nucleus.chat"));
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        PlayerData d = plugin.playerData().getIfLoaded(p.getUniqueId());
                        boolean ignoring = sender instanceof Player sp && d != null && d.isIgnoring(sp.getUniqueId());
                        if (!ignoring) {
                            p.sendMessage(line);
                        }
                    }
                    Bukkit.getConsoleSender().sendMessage(line);
                }))),
                "Beschreibt eine Aktion im Chat", "action", "emote");

        CommandKit.register(r, kit.root("broadcast", "nucleus.broadcast")
                .executes(kit.usage("/broadcast <nachricht>"))
                .then(greedy("message").executes(kit.run(ctx -> {
                    // Admin-Befehl: volles MiniMessage erlaubt
                    Component message = MiniMessage.miniMessage().deserialize(arg(ctx, "message"));
                    Bukkit.broadcast(plugin.messages().parse(module.settings().broadcastFormat(), "message", message));
                }))),
                "Nachricht an alle Spieler", "bc", "announce");
    }
}
