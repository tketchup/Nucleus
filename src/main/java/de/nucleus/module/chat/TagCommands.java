package de.nucleus.module.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.TagColors;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.sub;
import static de.nucleus.command.CommandKit.word;

/**
 * /tag: kosmetische Status-Tags für Chat und Tab-Liste.
 * Admins (nucleus.tag.admin): set, remove, create, delete, color, brackets, format, selectable, list, info.
 * Spieler (nucleus.tag.select, nur wenn tags.self-select an ist): select, clear.
 */
final class TagCommands {

    private static final String ADMIN = "nucleus.tag.admin";
    private static final String SELECT = "nucleus.tag.select";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,24}");

    private final NucleusPlugin plugin;
    private final ChatModule module;
    private final CommandKit kit;

    TagCommands(NucleusPlugin plugin, ChatModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        SuggestionProvider<CommandSourceStack> ids = CommandKit.suggest(ctx ->
                module.tags().all().stream().map(TagStore.Tag::id).toList());
        SuggestionProvider<CommandSourceStack> colors = CommandKit.suggest(ctx -> TagColors.suggestions());

        var root = kit.root("tag", null)
                .requires(src -> plugin.modules().isActive("chat")
                        && (src.getSender().hasPermission(ADMIN) || src.getSender().hasPermission(SELECT)))
                .executes(kit.run(ctx -> listSelectable(ctx)))
                // --- Admin ---
                .then(sub("set", ADMIN).then(word("player").suggests(CommandKit.players())
                        .then(word("id").suggests(ids).executes(kit.run(this::set)))))
                .then(sub("remove", ADMIN).then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(this::remove))))
                .then(sub("create", ADMIN).then(word("id")
                        .then(Commands.argument("text", StringArgumentType.string())
                                .executes(kit.run(ctx -> create(ctx, null)))
                                .then(greedy("color").suggests(colors)
                                        .executes(kit.run(ctx -> create(ctx, arg(ctx, "color"))))))))
                .then(sub("delete", ADMIN).then(word("id").suggests(ids).executes(kit.run(this::delete))))
                // Farben und Klammern enthalten Zeichen wie # : « [ , deshalb als Rest-Text
                .then(sub("color", ADMIN).then(word("id").suggests(ids)
                        .then(greedy("colors").suggests(colors).executes(kit.run(this::color)))))
                .then(sub("brackets", ADMIN).then(word("id").suggests(ids)
                        .then(greedy("brackets").suggests(CommandKit.suggest(ctx -> List.of("[ ]", "« »", "< >", "none")))
                                .executes(kit.run(this::brackets)))))
                .then(sub("format", ADMIN).then(word("id").suggests(ids)
                        .then(word("decoration").suggests(CommandKit.suggest(ctx -> {
                                    var list = new java.util.ArrayList<>(TagStore.DECORATIONS);
                                    list.add("none");
                                    return list;
                                }))
                                .executes(kit.run(ctx -> format(ctx, null)))
                                .then(word("state").suggests(CommandKit.suggest(ctx -> List.of("on", "off")))
                                        .executes(kit.run(ctx -> format(ctx, arg(ctx, "state"))))))))
                .then(sub("selectable", ADMIN).then(word("id").suggests(ids)
                        .then(word("state").suggests(CommandKit.suggest(ctx -> List.of("on", "off")))
                                .executes(kit.run(this::selectable)))))
                .then(sub("list", ADMIN).executes(kit.run(this::list)))
                .then(sub("info", ADMIN).then(word("id").suggests(ids).executes(kit.run(this::info))))
                // --- Spieler ---
                .then(sub("select", SELECT)
                        .executes(kit.run(this::listSelectable))
                        .then(word("id").suggests(CommandKit.suggest(ctx -> selectableIds(sender(ctx))))
                                .executes(kit.run(this::select))))
                .then(sub("clear", SELECT).executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    plugin.playerData().get(me).tag(null);
                    module.refreshTab(me);
                    plugin.messages().send(me, "tag.cleared");
                })));
        CommandKit.register(r, root, "Status-Tags für Chat und Tab-Liste", "tags");
    }

    // --- Spieler: auswählen ---

    private boolean selfSelectEnabled(CommandSender sender) {
        return module.settings().tagSelfSelect() || sender.hasPermission(ADMIN);
    }

    private boolean maySelect(CommandSender sender, TagStore.Tag tag) {
        return tag.selectable() || sender.hasPermission(tag.permission()) || sender.hasPermission(ADMIN);
    }

    private List<String> selectableIds(CommandSender sender) {
        return module.tags().all().stream().filter(t -> maySelect(sender, t)).map(TagStore.Tag::id).toList();
    }

    private void listSelectable(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!selfSelectEnabled(sender)) {
            throw new CommandFail("tag.self-select-off");
        }
        List<TagStore.Tag> tags = module.tags().all().stream().filter(t -> maySelect(sender, t)).toList();
        if (tags.isEmpty()) {
            throw new CommandFail("tag.none-selectable");
        }
        plugin.messages().send(sender, "tag.select-header");
        for (TagStore.Tag t : tags) {
            sender.sendMessage(plugin.messages().get("tag.select-entry", "id", t.id(), "tag", module.tags().component(t.id()))
                    .clickEvent(ClickEvent.runCommand("/tag select " + t.id())));
        }
    }

    private void select(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        if (!selfSelectEnabled(me)) {
            throw new CommandFail("tag.self-select-off");
        }
        TagStore.Tag tag = existing(arg(ctx, "id"));
        if (!maySelect(me, tag)) {
            throw new CommandFail("tag.not-allowed", "id", tag.id());
        }
        plugin.playerData().get(me).tag(tag.id());
        module.refreshTab(me);
        plugin.messages().send(me, "tag.selected", "tag", module.tags().component(tag.id()));
    }

    // --- Admin: vergeben ---

    private void set(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        TagStore.Tag tag = existing(arg(ctx, "id"));
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        plugin.playerData().withData(target.getUniqueId(), data -> {
            data.tag(tag.id());
            refreshIfOnline(target);
            plugin.messages().send(sender, "tag.set", "player", name, "tag", module.tags().component(tag.id()));
        });
    }

    private void remove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        OfflinePlayer target = CommandKit.known(ctx, "player");
        String name = target.getName() != null ? target.getName() : arg(ctx, "player");
        plugin.playerData().withData(target.getUniqueId(), data -> {
            data.tag(null);
            refreshIfOnline(target);
            plugin.messages().send(sender, "tag.removed", "player", name);
        });
    }

    private void refreshIfOnline(OfflinePlayer target) {
        Player p = Bukkit.getPlayer(target.getUniqueId());
        if (p != null) {
            module.refreshTab(p);
        }
    }

    // --- Admin: Tags bearbeiten ---

    private void create(CommandContext<CommandSourceStack> ctx, String color) {
        String id = arg(ctx, "id").toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new CommandFail("tag.invalid-id");
        }
        if (module.tags().get(id) != null) {
            throw new CommandFail("tag.exists", "id", id);
        }
        String text = arg(ctx, "text");
        if (text.isBlank() || text.length() > module.settings().tagMaxLength()) {
            throw new CommandFail("tag.invalid-text", "max", module.settings().tagMaxLength());
        }
        String textColor = color == null ? "white" : color(color);
        module.tags().put(new TagStore.Tag(id, text, "[", "]", "dark_gray", textColor, Set.of(), false));
        plugin.messages().send(sender(ctx), "tag.created", "id", id, "tag", module.tags().component(id));
    }

    private void delete(CommandContext<CommandSourceStack> ctx) {
        String id = existing(arg(ctx, "id")).id();
        module.tags().remove(id);
        module.refreshAllTabs(); // Träger zeigen den Tag dann nicht mehr
        plugin.messages().send(sender(ctx), "tag.deleted", "id", id);
    }

    private void color(CommandContext<CommandSourceStack> ctx) {
        TagStore.Tag tag = existing(arg(ctx, "id"));
        String[] parts = arg(ctx, "colors").trim().split("\\s+");
        String bracket = color(parts[0]);
        String text = parts.length > 1 ? color(parts[1]) : tag.textColor();
        update(ctx, tag.withColors(bracket, text));
    }

    private void brackets(CommandContext<CommandSourceStack> ctx) {
        TagStore.Tag tag = existing(arg(ctx, "id"));
        String input = arg(ctx, "brackets").trim();
        if (input.equalsIgnoreCase("none")) {
            update(ctx, tag.withBrackets("", ""));
            return;
        }
        String[] parts = input.split("\\s+");
        int max = module.settings().tagMaxBracket();
        if (parts.length != 2 || parts[0].length() > max || parts[1].length() > max) {
            throw new CommandFail("tag.invalid-brackets", "max", max);
        }
        update(ctx, tag.withBrackets(parts[0], parts[1]));
    }

    private void format(CommandContext<CommandSourceStack> ctx, String state) {
        TagStore.Tag tag = existing(arg(ctx, "id"));
        String deco = arg(ctx, "decoration").toLowerCase(Locale.ROOT);
        if (deco.equals("none")) {
            update(ctx, tag.withDecorations(Set.of()));
            return;
        }
        if (!TagStore.DECORATIONS.contains(deco)) {
            throw new CommandFail("tag.invalid-decoration");
        }
        Set<String> decos = new HashSet<>(tag.decorations());
        boolean on = state == null ? !decos.contains(deco) : onOff(state);
        if (on) {
            decos.add(deco);
        } else {
            decos.remove(deco);
        }
        update(ctx, tag.withDecorations(decos));
    }

    private void selectable(CommandContext<CommandSourceStack> ctx) {
        TagStore.Tag tag = existing(arg(ctx, "id"));
        update(ctx, tag.withSelectable(onOff(arg(ctx, "state"))));
    }

    /** Speichern, Tab-Liste aller Träger auffrischen, Vorschau zeigen. */
    private void update(CommandContext<CommandSourceStack> ctx, TagStore.Tag updated) {
        module.tags().put(updated);
        module.refreshAllTabs();
        plugin.messages().send(sender(ctx), "tag.updated", "id", updated.id(), "tag", module.tags().component(updated.id()));
    }

    private void list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (module.tags().all().isEmpty()) {
            throw new CommandFail("tag.none");
        }
        plugin.messages().send(sender, "tag.list-header", "count", module.tags().all().size());
        for (TagStore.Tag t : module.tags().all()) {
            Component line = plugin.messages().get(t.selectable() ? "tag.list-entry-selectable" : "tag.list-entry",
                    "id", t.id(), "tag", module.tags().component(t.id()));
            sender.sendMessage(line.clickEvent(ClickEvent.runCommand("/tag info " + t.id())));
        }
    }

    private void info(CommandContext<CommandSourceStack> ctx) {
        TagStore.Tag t = existing(arg(ctx, "id"));
        plugin.messages().send(sender(ctx), "tag.info", "id", t.id(), "tag", module.tags().component(t.id()),
                "text", t.text(), "brackets", t.bracketOpen().isEmpty() ? "-" : t.bracketOpen() + " " + t.bracketClose(),
                "bracket_color", t.bracketColor(), "text_color", t.textColor(),
                "decorations", t.decorations().isEmpty() ? "-" : String.join(", ", t.decorations()),
                "selectable", plugin.messages().raw(t.selectable() ? "tag.answer-yes" : "tag.answer-no"),
                "permission", t.permission());
    }

    // --- Hilfen ---

    private TagStore.Tag existing(String id) {
        TagStore.Tag tag = module.tags().get(id);
        if (tag == null) {
            throw new CommandFail("tag.unknown", "id", id);
        }
        return tag;
    }

    private static String color(String input) {
        return TagColors.normalize(input).orElseThrow(() -> new CommandFail("tag.invalid-color", "input", input));
    }

    private static boolean onOff(String state) {
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "on", "true", "an", "ja" -> true;
            case "off", "false", "aus", "nein" -> false;
            default -> throw new CommandFail("info.lines-invalid-switch");
        };
    }
}
