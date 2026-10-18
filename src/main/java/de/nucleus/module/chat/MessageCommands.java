package de.nucleus.module.chat;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.data.PlayerData;
import de.nucleus.util.PlayerLookup;
import de.nucleus.util.UserText;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /msg, /reply, /ignore, /socialspy. */
final class MessageCommands {

    private final NucleusPlugin plugin;
    private final ChatModule module;
    private final CommandKit kit;

    MessageCommands(NucleusPlugin plugin, ChatModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("msg", "nucleus.msg")
                .executes(kit.usage("/msg <spieler> <nachricht>"))
                .then(word("player").suggests(CommandKit.players())
                        .executes(kit.usage("/msg <spieler> <nachricht>"))
                        .then(greedy("message").executes(kit.run(ctx ->
                                send(sender(ctx), online(ctx, "player"), arg(ctx, "message")))))),
                "Schickt eine private Nachricht", "tell", "w", "whisper", "pm", "m");

        CommandKit.register(r, kit.root("reply", "nucleus.msg")
                .executes(kit.usage("/r <nachricht>"))
                .then(greedy("message").executes(kit.run(this::reply))),
                "Antwortet auf die letzte private Nachricht", "r");

        CommandKit.register(r, kit.root("ignore", "nucleus.ignore")
                .executes(kit.run(this::listIgnored))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(this::toggleIgnore))),
                "Ignoriert einen Spieler (oder hebt das auf)", "unignore");

        CommandKit.register(r, kit.root("socialspy", "nucleus.socialspy")
                .executes(kit.run(ctx -> {
                    Player player = self(ctx);
                    PlayerData data = plugin.playerData().get(player);
                    data.socialSpy(!data.socialSpy());
                    plugin.messages().send(player, data.socialSpy() ? "msg.spy-on" : "msg.spy-off");
                })),
                "Zeigt dir alle privaten Nachrichten an", "spy");
    }

    private void reply(CommandContext<CommandSourceStack> ctx) {
        CommandSender from = sender(ctx);
        UUID targetId = module.replyTarget(from);
        if (targetId == null) {
            throw new CommandFail("msg.reply-none");
        }
        CommandSender to = targetId.equals(ChatModule.CONSOLE) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(targetId);
        if (to == null || (to instanceof Player p && !PlayerLookup.canSee(from, p))) {
            throw new CommandFail("msg.reply-offline");
        }
        send(from, to, arg(ctx, "message"));
    }

    /** Gemeinsamer Versand für /msg und /reply, inklusive Ignorieren und SocialSpy. */
    private void send(CommandSender from, CommandSender to, String text) {
        if (from.equals(to)) {
            throw new CommandFail("msg.self");
        }
        if (from instanceof Player p && to instanceof Player target && !p.hasPermission("nucleus.ignore.exempt")) {
            if (plugin.playerData().get(target).isIgnoring(p.getUniqueId())) {
                throw new CommandFail("msg.ignored", "player", target.getName());
            }
        }
        Component body = UserText.parse(text, from, "nucleus.chat");
        Component fromName = displayName(from);
        Component toName = displayName(to);
        plugin.messages().send(from, "msg.sent", "target", toName, "message", body);
        plugin.messages().send(to, "msg.received", "sender", fromName, "message", body);
        module.rememberConversation(from, to);

        // SocialSpy: alle mit aktivem Spy (außer den Beteiligten) und die Konsole
        Component spy = plugin.messages().get("msg.spy", "sender", fromName, "target", toName, "message", body);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(from) || p.equals(to) || !p.hasPermission("nucleus.socialspy")) {
                continue;
            }
            PlayerData d = plugin.playerData().getIfLoaded(p.getUniqueId());
            if (d != null && d.socialSpy()) {
                p.sendMessage(spy);
            }
        }
        if (from instanceof Player && to instanceof Player) {
            Bukkit.getConsoleSender().sendMessage(spy);
        }
    }

    private Component displayName(CommandSender sender) {
        return sender instanceof Player p ? p.displayName() : plugin.messages().get("msg.console-name");
    }

    private void toggleIgnore(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        if (target.getUniqueId().equals(me.getUniqueId())) {
            throw new CommandFail("ignore.self");
        }
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        PlayerData data = plugin.playerData().get(me);
        if (!data.isIgnoring(target.getUniqueId()) && target instanceof Player online && online.hasPermission("nucleus.ignore.exempt")) {
            throw new CommandFail("ignore.exempt", "player", name);
        }
        boolean now = data.toggleIgnore(target.getUniqueId());
        plugin.messages().send(me, now ? "ignore.added" : "ignore.removed", "player", name);
    }

    private void listIgnored(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        List<String> names = new ArrayList<>();
        for (UUID id : plugin.playerData().get(me).ignored()) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            names.add(name != null ? name : id.toString());
        }
        if (names.isEmpty()) {
            throw new CommandFail("ignore.none");
        }
        plugin.messages().send(me, "ignore.list", "count", names.size(), "players", String.join(", ", names));
    }
}
