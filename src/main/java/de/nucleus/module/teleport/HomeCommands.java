package de.nucleus.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.core.TeleportService.Options;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.PlayerData;
import de.nucleus.util.PlayerLookup;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/**
 * /sethome, /home, /delhome, /homes. Mit nucleus.home.others lassen sich fremde Homes über
 * "spieler:home" ansprechen, auch wenn der Spieler offline ist.
 */
final class HomeCommands {

    private static final String DEFAULT_HOME = "home";
    private static final String OTHERS = "nucleus.home.others";

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    private final CommandKit kit;

    HomeCommands(NucleusPlugin plugin, TeleportModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("sethome", "nucleus.sethome")
                .executes(kit.run(ctx -> setHome(ctx, DEFAULT_HOME)))
                .then(word("name").executes(kit.run(ctx -> setHome(ctx, arg(ctx, "name"))))),
                "Setzt ein Home an deiner Position", "createhome");

        CommandKit.register(r, kit.root("home", "nucleus.home")
                .executes(kit.run(ctx -> home(ctx, null)))
                .then(greedy("name").suggests(homeSuggestions())
                        .executes(kit.run(ctx -> home(ctx, arg(ctx, "name"))))),
                "Teleportiert dich zu einem Home", "h");

        CommandKit.register(r, kit.root("delhome", "nucleus.delhome")
                .executes(kit.usage("/delhome <name>"))
                .then(greedy("name").suggests(homeSuggestions())
                        .executes(kit.run(ctx -> delHome(ctx, arg(ctx, "name"))))),
                "Löscht ein Home", "removehome");

        // /homes öffnet das GUI; /homes <spieler> zeigt fremde Homes als Liste im Chat
        CommandKit.register(r, kit.root("homes", "nucleus.home")
                .executes(kit.run(ctx -> {
                    Player player = self(ctx);
                    if (module.settings().guiEnabled()) {
                        module.menus().openHomes(player, 0);
                    } else {
                        sendList(player, plugin.playerData().get(player).homes(), "", module.homeLimit(player));
                    }
                }))
                .then(word("player").requires(src -> src.getSender().hasPermission(OTHERS))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> listOther(ctx, arg(ctx, "player"))))),
                "Listet deine Homes auf");
    }

    private void setHome(CommandContext<CommandSourceStack> ctx, String name) {
        module.setHome(self(ctx), name);
    }

    private void home(CommandContext<CommandSourceStack> ctx, String input) {
        Player player = self(ctx);
        if (input != null && input.contains(":")) {
            CommandKit.require(player, OTHERS);
            Target t = parseOther(player, input);
            plugin.playerData().withData(t.owner().getUniqueId(), data -> {
                NamedLocation loc = data.home(t.home());
                if (loc == null) {
                    plugin.messages().send(player, "home.not-found", "name", input);
                    return;
                }
                teleportHome(player, loc, input);
            });
            return;
        }
        PlayerData data = plugin.playerData().get(player);
        String name = input;
        if (name == null) {
            // Ohne Namen: "home", oder das einzige vorhandene Home
            name = data.homes().size() == 1 ? data.homes().keySet().iterator().next() : DEFAULT_HOME;
        }
        NamedLocation loc = data.home(name);
        if (loc == null) {
            if (data.homes().isEmpty()) {
                throw new CommandFail("home.none");
            }
            throw new CommandFail("home.not-found", "name", name);
        }
        teleportHome(player, loc, name);
    }

    private void teleportHome(Player player, NamedLocation loc, String name) {
        Location target = loc.toLocation();
        if (target == null) {
            plugin.messages().send(player, "teleport.world-missing", "world", loc.world());
            return;
        }
        plugin.teleports().teleport(player, target, Options.player("home"),
                () -> plugin.messages().send(player, "home.teleported", "name", name));
    }

    private void delHome(CommandContext<CommandSourceStack> ctx, String input) {
        CommandSender sender = sender(ctx);
        if (input.contains(":")) {
            CommandKit.require(sender, OTHERS);
            Target t = parseOther(sender, input);
            plugin.playerData().withData(t.owner().getUniqueId(), data -> {
                if (data.removeHome(t.home())) {
                    plugin.messages().send(sender, "home.deleted", "name", input);
                } else {
                    plugin.messages().send(sender, "home.not-found", "name", input);
                }
            });
            return;
        }
        Player player = self(ctx);
        if (!plugin.playerData().get(player).removeHome(input)) {
            throw new CommandFail("home.not-found", "name", input);
        }
        plugin.messages().send(player, "home.deleted", "name", input);
    }

    private void listOther(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSender sender = sender(ctx);
        OfflinePlayer owner = PlayerLookup.known(sender, name)
                .orElseThrow(() -> new CommandFail("error.player-not-found", "player", name));
        String ownerName = owner.getName() != null ? owner.getName() : name;
        plugin.playerData().withData(owner.getUniqueId(),
                data -> sendList(sender, data.homes(), ownerName + ":", -1));
    }

    /** Klickbare Liste; ein Klick teleportiert. limit &lt; 0 = Liste eines anderen Spielers. */
    private void sendList(CommandSender to, Map<String, NamedLocation> homes, String prefix, int limit) {
        if (homes.isEmpty()) {
            plugin.messages().send(to, limit < 0 ? "home.none-other" : "home.none");
            return;
        }
        List<Component> entries = new ArrayList<>();
        for (String name : homes.keySet()) {
            entries.add(plugin.messages().get("home.list-entry", "name", name)
                    .clickEvent(ClickEvent.runCommand("/home " + prefix + name)));
        }
        Component list = Component.join(JoinConfiguration.commas(true), entries);
        if (limit < 0) {
            plugin.messages().send(to, "home.list-other", "player", prefix.substring(0, prefix.length() - 1),
                    "count", homes.size(), "homes", list);
        } else {
            plugin.messages().send(to, "home.list", "count", homes.size(),
                    "limit", limit == Integer.MAX_VALUE ? "∞" : String.valueOf(limit), "homes", list);
        }
    }

    private record Target(OfflinePlayer owner, String home) {
    }

    private static Target parseOther(CommandSender sender, String input) {
        int idx = input.indexOf(':');
        String playerName = input.substring(0, idx);
        String home = input.substring(idx + 1);
        if (home.isEmpty()) {
            home = DEFAULT_HOME;
        }
        OfflinePlayer owner = PlayerLookup.known(sender, playerName)
                .orElseThrow(() -> new CommandFail("error.player-not-found", "player", playerName));
        return new Target(owner, home);
    }

    /** Eigene Homes; mit Fremd-Permission nach "spieler:" auch die Homes von Online-Spielern. */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> homeSuggestions() {
        return CommandKit.suggest(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            Collection<String> result = new ArrayList<>();
            if (sender instanceof Player p) {
                result.addAll(plugin.playerData().get(p).homes().keySet());
            }
            if (sender.hasPermission(OTHERS)) {
                for (Player other : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (PlayerLookup.canSee(sender, other)) {
                        PlayerData d = plugin.playerData().getIfLoaded(other.getUniqueId());
                        if (d != null) {
                            d.homes().keySet().forEach(h -> result.add(other.getName() + ":" + h));
                        }
                    }
                }
            }
            return result;
        });
    }
}
