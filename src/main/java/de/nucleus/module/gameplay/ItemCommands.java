package de.nucleus.module.gameplay;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.util.UserText;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.nucleus.command.CommandKit.arg;
import static de.nucleus.command.CommandKit.greedy;
import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.sub;
import static de.nucleus.command.CommandKit.word;

/**
 * /give, /item, /more, /repair, /enchant, /hat, /itemname, /itemlore, /skull, /powertool, /spawnmob.
 * Items werden über Paper-Datenkomponenten bearbeitet (DAMAGE, CUSTOM_NAME, LORE, PROFILE), nicht über
 * das alte ItemMeta.
 */
final class ItemCommands {

    private final NucleusPlugin plugin;
    private final GameplayModule module;
    private final CommandKit kit;

    ItemCommands(NucleusPlugin plugin, GameplayModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        // ArgumentTypes.itemStack versteht auch Komponenten, z. B. diamond_sword[enchantments={sharpness:5}]
        CommandKit.register(r, kit.root("give", "nucleus.give")
                .executes(kit.usage("/give <spieler> <item> [anzahl]"))
                .then(word("player").suggests(CommandKit.players())
                        .then(Commands.argument("item", ArgumentTypes.itemStack())
                                .executes(kit.run(ctx -> give(ctx, CommandKit.online(ctx, "player"), -1)))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(kit.run(ctx -> give(ctx, CommandKit.online(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount"))))))),
                "Gibt einem Spieler Items");

        CommandKit.register(r, kit.root("item", "nucleus.item")
                .executes(kit.usage("/item <item> [anzahl]"))
                .then(Commands.argument("item", ArgumentTypes.itemStack())
                        .executes(kit.run(ctx -> give(ctx, self(ctx), -1)))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(kit.run(ctx -> give(ctx, self(ctx), IntegerArgumentType.getInteger(ctx, "amount")))))),
                "Gibt dir Items", "i");

        CommandKit.register(r, kit.root("more", "nucleus.more")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    ItemStack hand = hand(me);
                    hand.setAmount(hand.getMaxStackSize());
                    plugin.messages().send(me, "gameplay.more", "amount", hand.getMaxStackSize());
                })),
                "Füllt den Stapel in der Hand auf");

        CommandKit.register(r, kit.root("repair", "nucleus.repair")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    if (!repair(hand(me))) {
                        throw new CommandFail("gameplay.repair-nothing");
                    }
                    plugin.messages().send(me, "gameplay.repaired", "count", 1);
                }))
                .then(sub("all", "nucleus.repair.all").executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    int count = 0;
                    // getContents enthält Inventar, Rüstung und Zweithand
                    for (ItemStack stack : me.getInventory().getContents()) {
                        if (stack != null && repair(stack)) {
                            count++;
                        }
                    }
                    plugin.messages().send(me, "gameplay.repaired", "count", count);
                }))),
                "Repariert das Item in der Hand (oder alle)", "fix");

        CommandKit.register(r, kit.root("enchant", "nucleus.enchant")
                .executes(kit.usage("/enchant <verzauberung> [stufe]"))
                .then(Commands.argument("enchantment", ArgumentTypes.resource(RegistryKey.ENCHANTMENT))
                        .executes(kit.run(ctx -> enchant(ctx, -1)))
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 255))
                                .executes(kit.run(ctx -> enchant(ctx, IntegerArgumentType.getInteger(ctx, "level")))))),
                "Verzaubert das Item in der Hand (Stufe 0 entfernt)");

        CommandKit.register(r, kit.root("hat", "nucleus.hat")
                .executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    PlayerInventory inv = me.getInventory();
                    ItemStack hand = hand(me);
                    ItemStack helmet = inv.getHelmet();
                    inv.setHelmet(hand);
                    inv.setItemInMainHand(helmet);
                    plugin.messages().send(me, "gameplay.hat");
                })),
                "Setzt das Item in der Hand als Hut auf", "head");

        CommandKit.register(r, kit.root("itemname", "nucleus.itemname")
                .executes(kit.usage("/itemname <name|reset>"))
                .then(greedy("name").suggests(CommandKit.suggest(ctx -> List.of("reset"))).executes(kit.run(ctx -> {
                    Player me = self(ctx);
                    ItemStack hand = hand(me);
                    String input = arg(ctx, "name");
                    if (input.equalsIgnoreCase("reset")) {
                        hand.resetData(DataComponentTypes.CUSTOM_NAME);
                        plugin.messages().send(me, "gameplay.itemname-reset");
                        return;
                    }
                    hand.setData(DataComponentTypes.CUSTOM_NAME, itemText(input, me));
                    plugin.messages().send(me, "gameplay.itemname-set");
                }))),
                "Benennt das Item in der Hand um", "rename");

        CommandKit.register(r, kit.root("itemlore", "nucleus.itemlore")
                .executes(kit.usage("/itemlore <add|set|remove|clear> ..."))
                .then(sub("add", null).then(greedy("text").executes(kit.run(ctx -> editLore(ctx, "add", -1)))))
                .then(sub("set", null).then(Commands.argument("line", IntegerArgumentType.integer(1, 64))
                        .then(greedy("text").executes(kit.run(ctx -> editLore(ctx, "set",
                                IntegerArgumentType.getInteger(ctx, "line")))))))
                .then(sub("remove", null).then(Commands.argument("line", IntegerArgumentType.integer(1, 64))
                        .executes(kit.run(ctx -> editLore(ctx, "remove", IntegerArgumentType.getInteger(ctx, "line"))))))
                .then(sub("clear", null).executes(kit.run(ctx -> editLore(ctx, "clear", -1)))),
                "Bearbeitet die Beschreibung des Items in der Hand", "lore");

        CommandKit.register(r, kit.root("skull", "nucleus.skull")
                .executes(kit.run(ctx -> skull(self(ctx), self(ctx).getName())))
                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.skull.others"))
                        .suggests(CommandKit.players())
                        .executes(kit.run(ctx -> skull(self(ctx), arg(ctx, "player"))))),
                "Gibt dir einen Spielerkopf", "playerhead");

        CommandKit.register(r, kit.root("powertool", "nucleus.powertool")
                .executes(kit.usage("/powertool <befehl|clear|list>"))
                .then(greedy("command").suggests(CommandKit.suggest(ctx -> List.of("clear", "list")))
                        .executes(kit.run(this::powertool))),
                "Bindet einen Befehl an das Item in der Hand (Linksklick)", "pt");

        CommandKit.register(r, kit.root("spawnmob", "nucleus.spawnmob")
                .executes(kit.usage("/spawnmob <mob> [anzahl] [spieler]"))
                .then(Commands.argument("mob", ArgumentTypes.resource(RegistryKey.ENTITY_TYPE))
                        .executes(kit.run(ctx -> spawnmob(ctx, 1, null)))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(kit.run(ctx -> spawnmob(ctx, IntegerArgumentType.getInteger(ctx, "amount"), null)))
                                .then(word("player").requires(src -> src.getSender().hasPermission("nucleus.spawnmob.others"))
                                        .suggests(CommandKit.players())
                                        .executes(kit.run(ctx -> spawnmob(ctx, IntegerArgumentType.getInteger(ctx, "amount"),
                                                CommandKit.online(ctx, "player"))))))),
                "Lässt Mobs erscheinen", "mob", "spawnentity");
    }

    // --- Items geben ---

    /** amount &lt; 0 = ein voller Stapel. Was nicht ins Inventar passt, fällt vor die Füße. */
    private void give(CommandContext<CommandSourceStack> ctx, Player target, int amount) {
        ItemStack template = ctx.getArgument("item", ItemStack.class);
        if (template.isEmpty()) {
            throw new CommandFail("gameplay.give-empty");
        }
        int total = amount < 0 ? template.getMaxStackSize() : amount;
        if (total > module.settings().maxGive()) {
            throw new CommandFail("gameplay.give-too-many", "max", module.settings().maxGive());
        }
        int remaining = total;
        int dropped = 0;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            int size = Math.min(remaining, stack.getMaxStackSize());
            stack.setAmount(size);
            remaining -= size;
            for (ItemStack overflow : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
                dropped += overflow.getAmount();
            }
        }
        Component itemName = template.effectiveName();
        CommandSender sender = sender(ctx);
        plugin.messages().send(target, "gameplay.given", "amount", total, "item", itemName);
        if (!sender.equals(target)) {
            plugin.messages().send(sender, "gameplay.given-other", "amount", total, "item", itemName, "player", target.getName());
        }
        if (dropped > 0) {
            plugin.messages().send(target, "gameplay.inventory-full", "amount", dropped);
        }
    }

    // --- Reparieren und Verzaubern ---

    /** @return true, wenn das Item beschädigt war und repariert wurde */
    private static boolean repair(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasData(DataComponentTypes.MAX_DAMAGE)) {
            return false;
        }
        Integer damage = stack.getData(DataComponentTypes.DAMAGE);
        if (damage == null || damage == 0) {
            return false;
        }
        stack.setData(DataComponentTypes.DAMAGE, 0);
        return true;
    }

    private void enchant(CommandContext<CommandSourceStack> ctx, int level) {
        Player me = self(ctx);
        ItemStack hand = hand(me);
        Enchantment ench = ctx.getArgument("enchantment", Enchantment.class);
        String name = ench.key().value();
        if (level == 0) {
            hand.removeEnchantment(ench);
            plugin.messages().send(me, "gameplay.enchant-removed", "enchantment", name);
            return;
        }
        int lvl = level < 0 ? ench.getMaxLevel() : level;
        boolean unsafe = me.hasPermission("nucleus.enchant.unsafe");
        if (!unsafe && (!ench.canEnchantItem(hand) || lvl > ench.getMaxLevel())) {
            throw new CommandFail("gameplay.enchant-unsafe", "enchantment", name, "max", ench.getMaxLevel());
        }
        hand.addUnsafeEnchantment(ench, lvl);
        plugin.messages().send(me, "gameplay.enchanted", "enchantment", name, "level", lvl);
    }

    // --- Name und Beschreibung ---

    /** Spielertext für Items: Formatierung per nucleus.itemname.*-Rechten, ohne Vanilla-Kursivschrift. */
    private static Component itemText(String input, Player who) {
        return UserText.parse(input, who, "nucleus.itemname").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void editLore(CommandContext<CommandSourceStack> ctx, String action, int line) {
        Player me = self(ctx);
        ItemStack hand = hand(me);
        ItemLore current = hand.getData(DataComponentTypes.LORE);
        List<Component> lines = new ArrayList<>(current == null ? List.of() : current.lines());
        switch (action) {
            case "add" -> lines.add(itemText(arg(ctx, "text"), me));
            case "set", "remove" -> {
                if (line > lines.size()) {
                    throw new CommandFail("gameplay.lore-no-line", "line", line, "count", lines.size());
                }
                if (action.equals("set")) {
                    lines.set(line - 1, itemText(arg(ctx, "text"), me));
                } else {
                    lines.remove(line - 1);
                }
            }
            default -> lines.clear();
        }
        if (lines.isEmpty()) {
            hand.resetData(DataComponentTypes.LORE);
        } else {
            hand.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        plugin.messages().send(me, "gameplay.lore-updated", "count", lines.size());
    }

    // --- Kopf, Powertool, Mobs ---

    /** Kopf nur mit Namen: Der Client lädt den Skin selbst (ResolvableProfile), kein Netzwerkzugriff auf dem Server. */
    private void skull(Player me, String owner) {
        if (!owner.matches("[A-Za-z0-9_]{1,16}")) {
            throw new CommandFail("error.player-not-found", "player", owner);
        }
        ItemStack head = ItemType.PLAYER_HEAD.createItemStack(1);
        head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().name(owner).build());
        for (ItemStack overflow : me.getInventory().addItem(head).values()) {
            me.getWorld().dropItemNaturally(me.getLocation(), overflow);
        }
        plugin.messages().send(me, "gameplay.skull", "player", owner);
    }

    private void powertool(CommandContext<CommandSourceStack> ctx) {
        Player me = self(ctx);
        var data = plugin.playerData().get(me);
        String input = arg(ctx, "command").trim();
        if (input.equalsIgnoreCase("list")) {
            Map<String, String> tools = data.powertools();
            if (tools.isEmpty()) {
                throw new CommandFail("gameplay.powertool-none");
            }
            tools.forEach((item, cmd) -> plugin.messages().send(me, "gameplay.powertool-entry", "item", item, "command", "/" + cmd));
            return;
        }
        if (input.equalsIgnoreCase("clear all")) {
            data.clearPowertools();
            plugin.messages().send(me, "gameplay.powertool-cleared-all");
            return;
        }
        String key = de.nucleus.gui.Icons.key(hand(me));
        if (input.equalsIgnoreCase("clear")) {
            data.powertool(key, null);
            plugin.messages().send(me, "gameplay.powertool-cleared", "item", key);
            return;
        }
        String command = input.startsWith("/") ? input.substring(1) : input;
        data.powertool(key, command);
        plugin.messages().send(me, "gameplay.powertool-set", "item", key, "command", "/" + command);
    }

    private void spawnmob(CommandContext<CommandSourceStack> ctx, int amount, Player at) {
        EntityType type = ctx.getArgument("mob", EntityType.class);
        if (!type.isSpawnable() || !type.isAlive() || type == EntityType.PLAYER) {
            throw new CommandFail("gameplay.spawnmob-invalid", "mob", type.key().value());
        }
        if (amount > module.settings().maxSpawnmob()) {
            throw new CommandFail("gameplay.spawnmob-too-many", "max", module.settings().maxSpawnmob());
        }
        Location loc;
        if (at != null) {
            loc = at.getLocation();
        } else {
            Player me = self(ctx);
            Block block = me.getTargetBlockExact(50);
            loc = block != null ? block.getLocation().add(0.5, 1, 0.5) : me.getLocation();
        }
        for (int i = 0; i < amount; i++) {
            loc.getWorld().spawnEntity(loc, type);
        }
        plugin.messages().send(sender(ctx), "gameplay.spawnmob", "amount", amount, "mob", type.key().value());
    }

    private static ItemStack hand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            throw new CommandFail("gameplay.empty-hand");
        }
        return hand;
    }
}
