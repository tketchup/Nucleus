package de.nucleus.module.gameplay;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /gamemode (+ /gmc /gms /gma /gmsp), /fly, /god, /heal, /feed, /speed, /burn, /extinguish, /suicide, /lightning. */
final class PlayerCommands {

    /** Eingaben für den Spielmodus, inklusive Kurzformen und Zahlen. */
    private static final Map<String, GameMode> MODES = Map.ofEntries(
            Map.entry("survival", GameMode.SURVIVAL), Map.entry("s", GameMode.SURVIVAL), Map.entry("0", GameMode.SURVIVAL),
            Map.entry("creative", GameMode.CREATIVE), Map.entry("c", GameMode.CREATIVE), Map.entry("1", GameMode.CREATIVE),
            Map.entry("adventure", GameMode.ADVENTURE), Map.entry("a", GameMode.ADVENTURE), Map.entry("2", GameMode.ADVENTURE),
            Map.entry("spectator", GameMode.SPECTATOR), Map.entry("sp", GameMode.SPECTATOR), Map.entry("3", GameMode.SPECTATOR));

    /** Spieler-Aktion für "/befehl [spieler]". */
    @FunctionalInterface
    private interface TargetAction {
        void run(CommandContext<CommandSourceStack> ctx, CommandSender sender, Player target);
    }

    private final NucleusPlugin plugin;
    private final GameplayModule module;
    private final CommandKit kit;

    PlayerCommands(NucleusPlugin plugin, GameplayModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("gamemode", "nucleus.gamemode")
                .executes(kit.usage("/gamemode <survival|creative|adventure|spectator> [spieler]"))
                .then(selfOrOther(word("mode").suggests(CommandKit.suggest(ctx ->
                                List.of("survival", "creative", "adventure", "spectator"))),
                        "nucleus.gamemode.others", (ctx, s, t) -> gamemode(s, t, parseMode(arg(ctx, "mode")))).build()),
                "Ändert den Spielmodus", "gm");
        shortcut(r, "gmc", GameMode.CREATIVE);
        shortcut(r, "gms", GameMode.SURVIVAL);
        shortcut(r, "gma", GameMode.ADVENTURE);
        shortcut(r, "gmsp", GameMode.SPECTATOR);

        CommandKit.register(r, selfOrOther(kit.root("fly", "nucleus.fly"), "nucleus.fly.others", (ctx, s, t) -> {
            boolean now = !t.getAllowFlight();
            module.setFly(t, now);
            notify(s, t, now ? "gameplay.fly-on" : "gameplay.fly-off");
        }), "Schaltet Fliegen an oder aus");

        CommandKit.register(r, selfOrOther(kit.root("god", "nucleus.god"), "nucleus.god.others", (ctx, s, t) -> {
            boolean now = !module.isGod(t);
            module.setGod(t, now);
            notify(s, t, now ? "gameplay.god-on" : "gameplay.god-off");
        }), "Unverwundbarkeit an oder aus", "godmode");

        CommandKit.register(r, selfOrOther(kit.root("heal", "nucleus.heal"), "nucleus.heal.others", (ctx, s, t) -> {
            AttributeInstance max = t.getAttribute(Attribute.MAX_HEALTH);
            t.setHealth(max != null ? max.getValue() : 20.0);
            t.setFoodLevel(20);
            t.setSaturation(20f);
            t.setFireTicks(0);
            for (PotionEffect effect : t.getActivePotionEffects()) {
                t.removePotionEffect(effect.getType());
            }
            notify(s, t, "gameplay.healed");
        }), "Heilt vollständig");

        CommandKit.register(r, selfOrOther(kit.root("feed", "nucleus.feed"), "nucleus.feed.others", (ctx, s, t) -> {
            t.setFoodLevel(20);
            t.setSaturation(20f);
            notify(s, t, "gameplay.fed");
        }), "Stillt den Hunger", "eat");

        CommandKit.register(r, kit.root("speed", "nucleus.speed")
                .executes(kit.usage("/speed <0-10> [fly|walk] [spieler]"))
                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 10))
                        .executes(kit.run(ctx -> speed(ctx, self(ctx), null)))
                        .then(word("type").suggests(CommandKit.suggest(ctx -> List.of("fly", "walk")))
                                .executes(kit.run(ctx -> speed(ctx, self(ctx), arg(ctx, "type"))))
                                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.speed.others"))
                                        .suggests(CommandKit.players())
                                        .executes(kit.run(ctx -> speed(ctx, CommandKit.online(ctx, "player"), arg(ctx, "type"))))))),
                "Setzt Lauf- oder Fluggeschwindigkeit (1 = normal)");

        CommandKit.register(r, kit.root("burn", "nucleus.burn")
                .executes(kit.usage("/burn <spieler> <sekunden>"))
                .then(word("player").suggests(CommandKit.players())
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600)).executes(kit.run(ctx -> {
                            Player t = CommandKit.online(ctx, "player");
                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            t.setFireTicks(seconds * 20);
                            plugin.messages().send(sender(ctx), "gameplay.burned", "player", t.getName(), "seconds", seconds);
                        })))),
                "Zündet einen Spieler an");

        CommandKit.register(r, selfOrOther(kit.root("extinguish", "nucleus.extinguish"), "nucleus.extinguish.others", (ctx, s, t) -> {
            t.setFireTicks(0);
            notify(s, t, "gameplay.extinguished");
        }), "Löscht Feuer", "ext");

        CommandKit.register(r, kit.root("suicide", "nucleus.suicide")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    module.setGod(me, false);
                    me.setHealth(0);
                })),
                "Tötet dich selbst");

        CommandKit.register(r, kit.root("lightning", "nucleus.lightning")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    Block block = me.getTargetBlockExact(100);
                    if (block == null) {
                        throw new CommandFail("gameplay.no-target-block");
                    }
                    me.getWorld().strikeLightning(block.getLocation().add(0.5, 1, 0.5));
                }))
                .then(word("player").suggests(CommandKit.players()).executes(kit.run(ctx -> {
                    Player t = CommandKit.online(ctx, "player");
                    Location loc = t.getLocation();
                    loc.getWorld().strikeLightning(loc);
                    plugin.messages().send(sender(ctx), "gameplay.lightning", "player", t.getName());
                }))),
                "Lässt einen Blitz einschlagen", "smite", "thor");
    }

    /** "/befehl" für sich selbst, "/befehl &lt;spieler&gt;" mit Fremd-Permission. */
    private <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T selfOrOther(
            T node, String othersPermission, TargetAction action) {
        return node.executes(kit.run(ctx -> action.run(ctx, sender(ctx), self(ctx))))
                .then(word("player").requires(src -> src.getSender().hasPermission(othersPermission))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> action.run(ctx, sender(ctx), CommandKit.online(ctx, "player")))));
    }

    private void shortcut(Commands r, String name, GameMode mode) {
        LiteralArgumentBuilder<CommandSourceStack> node = kit.root(name, "nucleus.gamemode");
        CommandKit.register(r, selfOrOther(node, "nucleus.gamemode.others", (ctx, s, t) -> gamemode(s, t, mode)),
                "Spielmodus " + mode.name().toLowerCase(Locale.ROOT));
    }

    private void gamemode(CommandSender sender, Player target, GameMode mode) {
        target.setGameMode(mode);
        notify(sender, target, "gameplay.gamemode", "mode", plugin.messages().raw("gameplay.mode." + mode.name().toLowerCase(Locale.ROOT)));
    }

    private static GameMode parseMode(String input) {
        GameMode mode = MODES.get(input.toLowerCase(Locale.ROOT));
        if (mode == null) {
            throw new CommandFail("gameplay.invalid-gamemode", "input", input);
        }
        return mode;
    }

    /**
     * 1 = Vanilla-Geschwindigkeit, 10 = Maximum, unter 1 langsamer. Ohne Typ zählt, ob der Spieler
     * gerade fliegt.
     */
    private void speed(CommandContext<CommandSourceStack> ctx, Player target, String type) {
        float value = FloatArgumentType.getFloat(ctx, "value");
        boolean fly = type == null ? target.isFlying() : switch (type.toLowerCase(Locale.ROOT)) {
            case "fly", "f" -> true;
            case "walk", "w" -> false;
            default -> throw new CommandFail("gameplay.invalid-speed-type", "input", type);
        };
        float base = fly ? 0.1f : 0.2f; // Vanilla-Standard
        float speed = value < 1 ? base * value : base + (value - 1) / 9f * (1f - base);
        if (fly) {
            target.setFlySpeed(Math.clamp(speed, 0f, 1f));
        } else {
            target.setWalkSpeed(Math.clamp(speed, 0f, 1f));
        }
        notify(sender(ctx), target, fly ? "gameplay.speed-fly" : "gameplay.speed-walk", "value", value);
    }

    /** Meldung an den Ziel-Spieler und, falls jemand anderes es ausgelöst hat, auch an ihn. */
    private void notify(CommandSender sender, Player target, String key, Object... placeholders) {
        plugin.messages().send(target, key, placeholders);
        if (!sender.equals(target)) {
            Object[] withPlayer = java.util.Arrays.copyOf(placeholders, placeholders.length + 2);
            withPlayer[placeholders.length] = "player";
            withPlayer[placeholders.length + 1] = target.getName();
            plugin.messages().send(sender, key + "-other", withPlayer);
        }
    }
}
