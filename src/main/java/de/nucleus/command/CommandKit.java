package de.nucleus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.nucleus.NucleusPlugin;
import de.nucleus.util.PlayerLookup;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Kleiner Baukasten für Brigadier-Befehle eines Moduls: Permission- und Modul-Prüfung, einheitliche
 * Fehlerbehandlung, Spieler-Argumente mit deutschen Fehlermeldungen und Tab-Completion.
 */
public final class CommandKit {

    /** Befehlslogik; darf {@link CommandFail} werfen. */
    @FunctionalInterface
    public interface Action {
        void run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private final NucleusPlugin plugin;
    private final String moduleId;

    public CommandKit(NucleusPlugin plugin, String moduleId) {
        this.plugin = plugin;
        this.moduleId = moduleId;
    }

    public NucleusPlugin plugin() {
        return plugin;
    }

    /**
     * Wurzelknoten: nur sichtbar und ausführbar, wenn das Modul aktiv ist und der Absender die
     * Permission hat. Spieler sehen gesperrte Befehle gar nicht erst in der Tab-Liste.
     */
    public LiteralArgumentBuilder<CommandSourceStack> root(String name, @Nullable String permission) {
        return Commands.literal(name).requires(src -> plugin.modules().isActive(moduleId)
                && (permission == null || src.getSender().hasPermission(permission)));
    }

    /** Unterknoten mit eigener Permission. */
    public static LiteralArgumentBuilder<CommandSourceStack> sub(String name, @Nullable String permission) {
        return Commands.literal(name).requires(src -> permission == null || src.getSender().hasPermission(permission));
    }

    public static RequiredArgumentBuilder<CommandSourceStack, String> word(String name) {
        return Commands.argument(name, StringArgumentType.word());
    }

    public static RequiredArgumentBuilder<CommandSourceStack, String> greedy(String name) {
        return Commands.argument(name, StringArgumentType.greedyString());
    }

    /** Wrappt Befehlslogik: {@link CommandFail} wird zur Nachricht, andere Fehler werden geloggt. */
    public Command<CommandSourceStack> run(Action action) {
        return ctx -> {
            try {
                action.run(ctx);
                return Command.SINGLE_SUCCESS;
            } catch (CommandFail fail) {
                plugin.messages().send(ctx.getSource().getSender(), fail.key(), fail.placeholders());
                return 0;
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Fehler bei /" + ctx.getInput(), e);
                plugin.messages().send(ctx.getSource().getSender(), "error.internal");
                return 0;
            }
        };
    }

    /** Für Befehle mit Pflichtargument: ohne Argument wird die Benutzung gezeigt. */
    public Command<CommandSourceStack> usage(String usage) {
        return ctx -> {
            plugin.messages().send(ctx.getSource().getSender(), "error.usage", "usage", plugin.messages().usage(usage));
            return 0;
        };
    }

    // --- Hilfen innerhalb von Befehlen ---

    public static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    /** Der ausführende Spieler (auch bei /execute as), sonst Abbruch mit "Nur für Spieler". */
    public static Player self(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            return p;
        }
        if (ctx.getSource().getSender() instanceof Player p) {
            return p;
        }
        throw new CommandFail("error.player-only");
    }

    public static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    /** Online-Spieler aus einem Wort-Argument, mit Sichtbarkeitsprüfung. */
    public static Player online(CommandContext<CommandSourceStack> ctx, String argName) {
        String name = arg(ctx, argName);
        return PlayerLookup.online(sender(ctx), name)
                .orElseThrow(() -> new CommandFail("error.player-not-found", "player", name));
    }

    /** Online- oder Offline-Spieler, der schon einmal da war. */
    public static OfflinePlayer known(CommandContext<CommandSourceStack> ctx, String argName) {
        String name = arg(ctx, argName);
        return PlayerLookup.known(sender(ctx), name)
                .orElseThrow(() -> new CommandFail("error.player-not-found", "player", name));
    }

    /** Welt-Argument über den Weltnamen ("world", "world_nether"), mit Vorschlägen. */
    public static RequiredArgumentBuilder<CommandSourceStack, String> worldArg(String name) {
        return word(name).suggests(suggest(ctx -> org.bukkit.Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList()));
    }

    public static org.bukkit.World world(CommandContext<CommandSourceStack> ctx, String argName) {
        String name = arg(ctx, argName);
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(name);
        if (world == null) {
            throw new CommandFail("error.world-not-found", "world", name);
        }
        return world;
    }

    public static void require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new CommandFail("error.no-permission");
        }
    }

    // --- Tab-Completion ---

    public static SuggestionProvider<CommandSourceStack> players() {
        return suggest(ctx -> PlayerLookup.visibleNames(ctx.getSource().getSender()));
    }

    public static SuggestionProvider<CommandSourceStack> suggest(
            Function<CommandContext<CommandSourceStack>, Collection<String>> values) {
        return (ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String value : values.apply(ctx)) {
                if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    /** Für übersetzte Befehlsbeschreibungen; wird vom Plugin beim Start gesetzt. */
    private static volatile de.nucleus.core.Messages descriptions;

    public static void descriptions(de.nucleus.core.Messages messages) {
        descriptions = messages;
    }

    /**
     * Meldet einen fertigen Befehl mit Beschreibung und Aliassen an. Die Beschreibung kommt aus
     * commands.&lt;befehl&gt; der Sprachdatei, der Text im Code ist nur der Ersatz.
     */
    public static void register(Commands registrar, LiteralArgumentBuilder<CommandSourceStack> node,
                                String description, String... aliases) {
        de.nucleus.core.Messages m = descriptions;
        String text = m == null ? description : m.commandDescription(node.getLiteral(), description);
        registrar.register(node.build(), text, List.of(aliases));
    }
}
