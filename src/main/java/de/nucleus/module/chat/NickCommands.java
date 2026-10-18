package de.nucleus.module.chat;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.PlayerLookup;
import de.nucleus.util.UserText;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/**
 * /nick &lt;name|off&gt;, /nick &lt;spieler&gt; &lt;name|off&gt; und /realname &lt;nick&gt;.
 *
 * <p>Der Nick wird beim Setzen mit den Rechten des Setzenden geprüft (nucleus.nick.color/.hex/
 * .format/.minimessage) und als bereinigtes MiniMessage gespeichert.
 */
final class NickCommands {

    private static final String OTHERS = "nucleus.nick.others";

    private final NucleusPlugin plugin;
    private final ChatModule module;
    private final CommandKit kit;

    NickCommands(NucleusPlugin plugin, ChatModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("nick", "nucleus.nick")
                .executes(kit.usage("/nick <name|off> oder /nick <spieler> <name|off>"))
                // greedy: MiniMessage-Tags wie <#ff0000> passen nicht in ein Brigadier-"word"
                .then(greedy("input").suggests(CommandKit.suggest(ctx -> java.util.List.of("off")))
                        .executes(kit.run(this::nick))),
                "Setzt deinen Nicknamen", "nickname");

        CommandKit.register(r, kit.root("realname", "nucleus.realname")
                .executes(kit.usage("/realname <nick>"))
                .then(word("nick").executes(kit.run(this::realname))),
                "Zeigt den echten Namen zu einem Nick");
    }

    private void nick(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        String input = arg(ctx, "input").trim();
        Player target;
        String nickText;
        int space = input.indexOf(' ');
        if (space > 0 && sender.hasPermission(OTHERS)) {
            // "/nick <spieler> <nick>"
            String name = input.substring(0, space);
            target = PlayerLookup.online(sender, name)
                    .orElseThrow(() -> new CommandFail("error.player-not-found", "player", name));
            nickText = input.substring(space + 1).trim();
        } else {
            target = self(ctx);
            nickText = input;
        }

        if (nickText.equalsIgnoreCase("off")) {
            plugin.playerData().get(target).nick(null);
            module.nicks().set(target.getUniqueId(), null);
            module.applyNick(target);
            notify(sender, target, "nick.removed", "nick.removed-other", null);
            return;
        }

        ChatModule.Settings s = module.settings();
        Component parsed = UserText.parse(nickText, sender, "nucleus.nick");
        String plain = PlainTextComponentSerializer.plainText().serialize(parsed);
        if (plain.length() < s.nickMinLength() || plain.length() > s.nickMaxLength()) {
            throw new CommandFail("nick.length", "min", s.nickMinLength(), "max", s.nickMaxLength());
        }
        if (!s.nickPattern().matcher(plain).matches()) {
            throw new CommandFail("nick.invalid");
        }
        if (isTaken(plain, target.getUniqueId())) {
            throw new CommandFail("nick.taken", "nick", plain);
        }

        plugin.playerData().get(target).nick(MiniMessage.miniMessage().serialize(parsed));
        module.nicks().set(target.getUniqueId(), plain);
        module.applyNick(target);
        notify(sender, target, "nick.set", "nick.set-other", target.displayName());
    }

    /** Vergeben, wenn ein anderer Spieler so heißt oder diesen Nick schon hat. */
    private boolean isTaken(String plain, UUID self) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(self) && p.getName().equalsIgnoreCase(plain)) {
                return true;
            }
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(plain);
        if (known != null && !known.getUniqueId().equals(self)) {
            return true;
        }
        UUID owner = module.nicks().owner(plain);
        return owner != null && !owner.equals(self);
    }

    private void notify(CommandSender sender, Player target, String selfKey, String otherKey, Component nick) {
        Object[] ph = nick == null ? new Object[0] : new Object[]{"nick", nick};
        plugin.messages().send(target, selfKey, ph);
        if (!sender.equals(target)) {
            Object[] phOther = nick == null ? new Object[]{"player", target.getName()}
                    : new Object[]{"player", target.getName(), "nick", nick};
            plugin.messages().send(sender, otherKey, phOther);
        }
    }

    private void realname(CommandContext<CommandSourceStack> ctx) {
        String nick = arg(ctx, "nick").replaceFirst("^[^A-Za-z0-9_]+", "");
        UUID owner = module.nicks().owner(nick);
        if (owner == null) {
            // auch Anzeigenamen anderer Plugins berücksichtigen
            Optional<Player> byDisplay = Bukkit.getOnlinePlayers().stream()
                    .filter(p -> PlayerLookup.plainDisplayName(p).replaceFirst("^[^A-Za-z0-9_]+", "").equalsIgnoreCase(nick))
                    .map(p -> (Player) p).findFirst();
            owner = byDisplay.map(Player::getUniqueId).orElse(null);
        }
        if (owner == null) {
            throw new CommandFail("nick.unknown", "nick", nick);
        }
        String name = Bukkit.getOfflinePlayer(owner).getName();
        plugin.messages().send(sender(ctx), "nick.realname", "nick", nick, "player", name != null ? name : owner.toString());
    }
}
