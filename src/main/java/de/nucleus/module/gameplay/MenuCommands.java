package de.nucleus.module.gameplay;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandKit;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;

import static de.nucleus.command.CommandKit.self;

/**
 * Tragbare Werkbänke und Mülleimer. Menüs werden über {@link MenuType} gebaut; die alten
 * openWorkbench/openAnvil-Methoden sind in 26.3 deprecated.
 */
final class MenuCommands {

    private final NucleusPlugin plugin;
    private final CommandKit kit;

    MenuCommands(NucleusPlugin plugin, CommandKit kit) {
        this.plugin = plugin;
        this.kit = kit;
    }

    void register(Commands r) {
        // checkReachable(false): das Menü bleibt offen, obwohl kein echter Block in der Nähe ist
        menu(r, "workbench", "nucleus.workbench", "Öffnet eine Werkbank",
                p -> MenuType.CRAFTING.builder().checkReachable(false).build(p), "craft", "wb");
        menu(r, "anvil", "nucleus.anvil", "Öffnet einen Amboss",
                p -> MenuType.ANVIL.builder().checkReachable(false).build(p));
        menu(r, "grindstone", "nucleus.grindstone", "Öffnet einen Schleifstein",
                p -> MenuType.GRINDSTONE.builder().checkReachable(false).build(p));
        menu(r, "stonecutter", "nucleus.stonecutter", "Öffnet eine Steinsäge",
                p -> MenuType.STONECUTTER.builder().checkReachable(false).build(p));
        menu(r, "loom", "nucleus.loom", "Öffnet einen Webstuhl",
                p -> MenuType.LOOM.builder().checkReachable(false).build(p));
        menu(r, "cartography", "nucleus.cartography", "Öffnet einen Kartentisch",
                p -> MenuType.CARTOGRAPHY_TABLE.builder().checkReachable(false).build(p), "cartographytable");
        menu(r, "smithing", "nucleus.smithing", "Öffnet einen Schmiedetisch",
                p -> MenuType.SMITHING.builder().checkReachable(false).build(p), "smithingtable");
        // Mülleimer: ein leeres Truhenmenü ohne Block; was beim Schließen drin liegt, ist weg
        menu(r, "trash", "nucleus.trash", "Öffnet einen Mülleimer",
                p -> MenuType.GENERIC_9X4.builder().title(plugin.messages().get("gameplay.trash-title")).build(p), "disposal");
    }

    private void menu(Commands r, String name, String permission, String description,
                      java.util.function.Function<Player, org.bukkit.inventory.InventoryView> factory, String... aliases) {
        CommandKit.register(r, kit.root(name, permission).executes(kit.run(ctx -> {
            Player me = self(ctx);
            me.openInventory(factory.apply(me));
        })), description, aliases);
    }
}
