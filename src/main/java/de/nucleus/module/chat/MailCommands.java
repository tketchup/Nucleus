package de.nucleus.module.chat;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.data.PlayerData;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.sub;
import static de.nucleus.command.CommandKit.word;

/** /mail send|read|clear. Mails an Offline-Spieler werden asynchron in deren Datei geschrieben. */
final class MailCommands {


    private final NucleusPlugin plugin;
    private final ChatModule module;
    private final CommandKit kit;

    MailCommands(NucleusPlugin plugin, ChatModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("mail", "nucleus.mail")
                .executes(kit.run(this::read))
                .then(sub("read", null).executes(kit.run(this::read)))
                .then(sub("clear", null).executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    plugin.playerData().get(me).clearMails();
                    plugin.messages().send(me, "mail.cleared");
                })))
                .then(sub("send", "nucleus.mail.send")
                        .executes(kit.usage("/mail send <spieler> <nachricht>"))
                        .then(word("player").suggests(CommandKit.players())
                                .executes(kit.usage("/mail send <spieler> <nachricht>"))
                                .then(greedy("message").executes(kit.run(this::send))))),
                "Postfach: lesen, senden, leeren", "email");
    }

    private void send(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String targetName = target.getName() != null ? target.getName() : arg(ctx, "player");
        String text = arg(ctx, "message");
        PlayerData.Mail mail = new PlayerData.Mail(sender instanceof Player ? sender.getName() : plugin.messages().raw("general.console"), System.currentTimeMillis(), text, false);
        int max = module.settings().maxMails();
        plugin.playerData().withData(target.getUniqueId(), data -> {
            if (sender instanceof Player p && data.isIgnoring(p.getUniqueId()) && !p.hasPermission("nucleus.ignore.exempt")) {
                plugin.messages().send(sender, "msg.ignored", "player", targetName);
                return;
            }
            data.addMail(mail, max);
            plugin.messages().send(sender, "mail.sent", "player", targetName);
            Player online = Bukkit.getPlayer(target.getUniqueId());
            if (online != null) {
                plugin.messages().send(online, "mail.received", "player", sender.getName());
            }
        });
    }

    private void read(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        PlayerData data = plugin.playerData().get(me);
        if (data.mails().isEmpty()) {
            throw new CommandFail("mail.none");
        }
        plugin.messages().send(me, "mail.header", "count", data.mails().size());
        for (PlayerData.Mail m : data.mails()) {
            plugin.messages().send(me, m.read() ? "mail.entry" : "mail.entry-unread",
                    "player", m.from(), "date", plugin.messages().date(m.time()), "message", m.text());
        }
        data.markMailsRead();
        plugin.messages().send(me, "mail.footer");
    }
}
