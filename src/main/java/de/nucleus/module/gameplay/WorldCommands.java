package de.nucleus.module.gameplay;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.GameTime;
import de.nucleus.util.TimeParser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.word;

/** /time, /day, /night, /weather, /sun, /rain, /storm, /ptime, /pweather. */
final class WorldCommands {

    private static final List<String> TIME_WORDS = List.of("day", "noon", "sunset", "night", "midnight", "sunrise");
    /** Standarddauer für Wetter ohne Angabe: 10 Minuten. */
    private static final int DEFAULT_WEATHER_TICKS = 20 * 60 * 10;

    private final NucleusPlugin plugin;
    private final CommandKit kit;

    WorldCommands(NucleusPlugin plugin, CommandKit kit) {
        this.plugin = plugin;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("time", "nucleus.time")
                .executes(kit.run(ctx -> showTime(sender(ctx), defaultWorld(ctx))))
                // greedy, weil "6:30" einen Doppelpunkt enthält; ein Weltname darf am Ende stehen
                .then(CommandKit.greedy("value").suggests(CommandKit.suggest(ctx -> TIME_WORDS))
                        .executes(kit.run(this::setTime))),
                "Zeigt oder setzt die Uhrzeit (day, night, 6:30, 1000 ...)");
        fixedTime(r, "day", 1000, "Setzt die Zeit auf Tag");
        fixedTime(r, "night", 13000, "Setzt die Zeit auf Nacht");

        CommandKit.register(r, kit.root("weather", "nucleus.weather")
                .executes(kit.usage("/weather <clear|rain|storm> [dauer] [welt]"))
                .then(word("type").suggests(CommandKit.suggest(ctx -> List.of("clear", "rain", "storm")))
                        .executes(kit.run(ctx -> weather(ctx, arg(ctx, "type"), null, defaultWorld(ctx))))
                        .then(word("duration").suggests(CommandKit.suggest(ctx -> List.of("5m", "30m", "1h")))
                                .executes(kit.run(ctx -> weather(ctx, arg(ctx, "type"), arg(ctx, "duration"), defaultWorld(ctx))))
                                .then(CommandKit.worldArg("world")
                                        .executes(kit.run(ctx -> weather(ctx, arg(ctx, "type"), arg(ctx, "duration"),
                                                CommandKit.world(ctx, "world"))))))),
                "Ändert das Wetter");
        fixedWeather(r, "sun", "clear", "Klares Wetter");
        fixedWeather(r, "rain", "rain", "Regen");
        fixedWeather(r, "storm", "storm", "Gewitter", "thunder");

        CommandKit.register(r, kit.root("ptime", "nucleus.ptime")
                .executes(kit.usage("/ptime <day|night|6:30|reset> [spieler]"))
                .then(CommandKit.greedy("value").suggests(CommandKit.suggest(ctx -> List.of("day", "night", "noon", "midnight", "reset")))
                        .executes(kit.run(this::ptime))),
                "Eigene Uhrzeit nur für dich");

        CommandKit.register(r, kit.root("pweather", "nucleus.pweather")
                .executes(kit.usage("/pweather <clear|rain|reset> [spieler]"))
                .then(word("type").suggests(CommandKit.suggest(ctx -> List.of("clear", "rain", "reset")))
                        .executes(kit.run(ctx -> pweather(ctx, self(ctx))))
                        .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.pweather.others"))
                                .suggests(CommandKit.players())
                                .executes(kit.run(ctx -> pweather(ctx, CommandKit.online(ctx, "player")))))),
                "Eigenes Wetter nur für dich");
    }

    private void fixedTime(Commands r, String name, long ticks, String description) {
        CommandKit.register(r, kit.root(name, "nucleus.time")
                .executes(kit.run(ctx -> applyTime(sender(ctx), defaultWorld(ctx), ticks)))
                .then(CommandKit.worldArg("world")
                        .executes(kit.run(ctx -> applyTime(sender(ctx), CommandKit.world(ctx, "world"), ticks)))),
                description);
    }

    private void fixedWeather(Commands r, String name, String type, String description, String... aliases) {
        CommandKit.register(r, kit.root(name, "nucleus.weather")
                .executes(kit.run(ctx -> weather(ctx, type, null, defaultWorld(ctx))))
                .then(CommandKit.worldArg("world")
                        .executes(kit.run(ctx -> weather(ctx, type, null, CommandKit.world(ctx, "world"))))),
                description, aliases);
    }

    /** Welt des Spielers, von der Konsole die Hauptwelt. */
    private static World defaultWorld(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
    }

    private void showTime(CommandSender sender, World world) {
        if (world.isFixedTime()) {
            throw new CommandFail("gameplay.time-fixed", "world", world.getName());
        }
        long t = world.getTime();
        plugin.messages().send(sender, "gameplay.time-show", "world", world.getName(), "clock", GameTime.clock(t), "ticks", t);
    }

    /** "/time 6:30" oder "/time 6:30 world_nether". */
    private void setTime(CommandContext<CommandSourceStack> ctx) {
        String[] parts = arg(ctx, "value").trim().split("\\s+");
        World world = defaultWorld(ctx);
        if (parts.length == 2) {
            world = Bukkit.getWorld(parts[1]);
            if (world == null) {
                throw new CommandFail("error.world-not-found", "world", parts[1]);
            }
        } else if (parts.length > 2) {
            throw new CommandFail("error.usage", "usage", plugin.messages().usage("/time [zeit] [welt]"));
        }
        String input = parts[0];
        long ticks = GameTime.parse(input).orElseThrow(() -> new CommandFail("gameplay.invalid-time", "input", input));
        applyTime(sender(ctx), world, ticks);
    }

    private void applyTime(CommandSender sender, World world, long ticks) {
        // In 26.x haben nur Welten mit eigener Welt-Uhr eine Tageszeit; Nether und End nicht
        if (world.isFixedTime()) {
            throw new CommandFail("gameplay.time-fixed", "world", world.getName());
        }
        world.setTime(ticks);
        plugin.messages().send(sender, "gameplay.time-set", "world", world.getName(), "clock", GameTime.clock(ticks), "ticks", ticks);
    }

    private void weather(CommandContext<CommandSourceStack> ctx, String type, String durationText, World world) {
        int ticks = DEFAULT_WEATHER_TICKS;
        if (durationText != null) {
            Duration d = TimeParser.parse(durationText)
                    .orElseThrow(() -> new CommandFail("moderation.invalid-duration", "input", durationText));
            ticks = (int) Math.min(Integer.MAX_VALUE, d.toSeconds() * 20);
        }
        String key;
        switch (type.toLowerCase(Locale.ROOT)) {
            case "clear", "sun" -> {
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(ticks);
                key = "gameplay.weather-clear";
            }
            case "rain" -> {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(ticks);
                key = "gameplay.weather-rain";
            }
            case "storm", "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(ticks);
                world.setThunderDuration(ticks);
                key = "gameplay.weather-storm";
            }
            default -> throw new CommandFail("gameplay.invalid-weather", "input", type);
        }
        plugin.messages().send(sender(ctx), key, "world", world.getName(),
                "time", plugin.messages().duration(Duration.ofSeconds(ticks / 20)));
    }

    /** "/ptime 6:30" oder "/ptime 6:30 Spieler" (mit nucleus.ptime.others). */
    private void ptime(CommandContext<CommandSourceStack> ctx) {
        String[] parts = arg(ctx, "value").trim().split("\\s+");
        Player target;
        if (parts.length == 2) {
            CommandKit.require(sender(ctx), "nucleus.ptime.others");
            target = de.nucleus.util.PlayerLookup.online(sender(ctx), parts[1])
                    .orElseThrow(() -> new CommandFail("error.player-not-found", "player", parts[1]));
        } else if (parts.length == 1) {
            target = self(ctx);
        } else {
            throw new CommandFail("error.usage", "usage", plugin.messages().usage("/ptime <zeit|reset> [spieler]"));
        }
        String input = parts[0];
        if (input.equalsIgnoreCase("reset")) {
            target.resetPlayerTime();
            plugin.messages().send(sender(ctx), "gameplay.ptime-reset", "player", target.getName());
            return;
        }
        long ticks = GameTime.parse(input).orElseThrow(() -> new CommandFail("gameplay.invalid-time", "input", input));
        target.setPlayerTime(ticks, false); // false: feste Zeit, läuft nicht weiter
        plugin.messages().send(sender(ctx), "gameplay.ptime-set", "player", target.getName(), "clock", GameTime.clock(ticks));
    }

    private void pweather(CommandContext<CommandSourceStack> ctx, Player target) {
        String input = arg(ctx, "type").toLowerCase(Locale.ROOT);
        switch (input) {
            case "reset" -> target.resetPlayerWeather();
            case "clear", "sun" -> target.setPlayerWeather(WeatherType.CLEAR);
            case "rain", "downfall" -> target.setPlayerWeather(WeatherType.DOWNFALL);
            default -> throw new CommandFail("gameplay.invalid-weather", "input", input);
        }
        plugin.messages().send(sender(ctx), "gameplay.pweather-set", "player", target.getName(), "weather", input);
    }
}
