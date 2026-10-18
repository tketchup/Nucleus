package de.nucleus.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.core.TeleportService.Options;
import de.nucleus.data.NamedLocation;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.online;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /setwarp, /warp, /delwarp, /warps. Optional eigene Permission pro Warp: nucleus.warp.&lt;name&gt;. */
final class WarpCommands {

    private static final String OTHERS = "nucleus.warp.others";
    /** Namen, die mit Unter-Permissions kollidieren würden. */
    private static final Set<String> RESERVED = Set.of("others");

    private final NucleusPlugin plugin;
    private final TeleportModule module;
    private final CommandKit kit;

    WarpCommands(NucleusPlugin plugin, TeleportModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("setwarp", "nucleus.setwarp")
                .executes(kit.usage("/setwarp <name>"))
                .then(word("name").executes(kit.run(this::setWarp))),
                "Setzt einen Warp an deiner Position", "createwarp");

        CommandKit.register(r, kit.root("warp", "nucleus.warp")
                .executes(kit.run(this::openOrList))
                .then(word("name").suggests(warpSuggestions())
                        .executes(kit.run(ctx -> warp(ctx, false)))
                        .then(word("player").requires(src -> src.getSender().hasPermission(OTHERS))
                                .suggests(CommandKit.players())
                                .executes(kit.run(ctx -> warp(ctx, true))))),
                "Teleportiert zu einem Warp");

        CommandKit.register(r, kit.root("delwarp", "nucleus.delwarp")
                .executes(kit.usage("/delwarp <name>"))
                .then(word("name").suggests(CommandKit.suggest(ctx -> module.warps().all().keySet()))
                        .executes(kit.run(this::delWarp))),
                "Löscht einen Warp", "removewarp");

        CommandKit.register(r, kit.root("warps", "nucleus.warp")
                .executes(kit.run(this::openOrList)),
                "Listet alle Warps auf");
    }

    private void setWarp(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        String name = arg(ctx, "name");
        if (!TeleportModule.NAME.matcher(name).matches() || RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw new CommandFail("warp.invalid-name");
        }
        boolean isNew = module.warps().set(name, NamedLocation.of(player.getLocation()));
        plugin.messages().send(player, isNew ? "warp.set" : "warp.updated", "name", name);
    }

    private void warp(CommandContext<CommandSourceStack> ctx, boolean other) {
        CommandSender sender = sender(ctx);
        String name = arg(ctx, "name");
        NamedLocation loc = module.warps().get(name);
        if (loc == null || !canUse(sender, name)) {
            throw new CommandFail("warp.not-found", "name", name);
        }
        Location target = loc.toLocation();
        if (target == null) {
            throw new CommandFail("teleport.world-missing", "world", loc.world());
        }
        if (other) {
            Player victim = online(ctx, "player");
            plugin.teleports().teleport(victim, target, Options.admin(), () -> {
                plugin.messages().send(victim, "warp.teleported", "name", name);
                plugin.messages().send(sender, "warp.teleported-other", "name", name, "player", victim.getName());
            });
        } else {
            Player player = self(ctx);
            plugin.teleports().teleport(player, target, Options.player("warp"),
                    () -> plugin.messages().send(player, "warp.teleported", "name", name));
        }
    }

    private void delWarp(CommandContext<CommandSourceStack> ctx) {
        String name = arg(ctx, "name");
        if (!module.warps().remove(name)) {
            throw new CommandFail("warp.not-found", "name", name);
        }
        plugin.messages().send(sender(ctx), "warp.deleted", "name", name);
    }

    /** Spieler bekommen das GUI (falls in teleport.yml an), sonst eine anklickbare Liste. */
    private void openOrList(CommandContext<CommandSourceStack> ctx) {
        if (sender(ctx) instanceof Player p && module.settings().guiEnabled()) {
            module.menus().openWarps(p, 0);
        } else {
            list(ctx);
        }
    }

    private void list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        List<Component> entries = new ArrayList<>();
        for (String name : module.warps().all().keySet()) {
            if (canUse(sender, name)) {
                entries.add(plugin.messages().get("warp.list-entry", "name", name)
                        .clickEvent(ClickEvent.runCommand("/warp " + name)));
            }
        }
        if (entries.isEmpty()) {
            throw new CommandFail("warp.none");
        }
        plugin.messages().send(sender, "warp.list", "count", entries.size(),
                "warps", Component.join(JoinConfiguration.commas(true), entries));
    }

    private boolean canUse(CommandSender sender, String warp) {
        return module.canUseWarp(sender, warp);
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> warpSuggestions() {
        return CommandKit.suggest(ctx -> module.warps().all().keySet().stream()
                .filter(w -> canUse(ctx.getSource().getSender(), w)).toList());
    }
}
