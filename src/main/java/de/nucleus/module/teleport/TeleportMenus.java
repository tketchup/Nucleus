package de.nucleus.module.teleport;

import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.core.TeleportService.Options;
import de.nucleus.data.NamedLocation;
import de.nucleus.data.PlayerData;
import de.nucleus.gui.Dialogs;
import de.nucleus.gui.Icons;
import de.nucleus.gui.Menu;
import de.nucleus.util.Formats;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GUI für Homes und Warps: Linksklick teleportiert, Rechtsklick öffnet "Bearbeiten" (umbenennen
 * per Dialog, Symbol durch Klick auf ein Item im eigenen Inventar, löschen mit Bestätigung).
 */
final class TeleportMenus {

    /** Einträge pro Seite: 5 Reihen, die unterste Reihe ist die Leiste. */
    private static final int PAGE_SIZE = 45;
    private static final Set<String> RESERVED_WARPS = Set.of("others");

    /** Ein Home oder Warp, wie das Menü es braucht. */
    private interface Entry {
        String name();

        @Nullable String icon();

        NamedLocation location();

        ItemType defaultIcon();

        /** @return false, wenn der neue Name belegt ist */
        boolean rename(String newName);

        void icon(String itemKey);

        void delete();

        boolean canEdit(Player player);

        boolean canDelete(Player player);

        void teleport(Player player);

        /** Zur Liste zurück, in der der Eintrag steht. */
        void backToList(Player player);
    }

    private final NucleusPlugin plugin;
    private final TeleportModule module;

    TeleportMenus(NucleusPlugin plugin, TeleportModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    // --- Listen ---

    void openHomes(Player player, int page) {
        PlayerData data = plugin.playerData().get(player);
        List<Entry> entries = new ArrayList<>();
        for (String name : data.homes().keySet()) {
            entries.add(homeEntry(player, name));
        }
        int limit = module.homeLimit(player);
        boolean canAdd = player.hasPermission("nucleus.sethome") && data.homes().size() < limit;
        openList(player, plugin.messages().get("gui.homes-title"), entries, page,
                canAdd ? newButton("gui.new-home", ItemType.RED_BED) : null,
                canAdd ? p -> askNewName(p, "gui.new-home", name -> {
                    module.setHome(p, name);
                    openHomes(p, page);
                }) : null,
                p2 -> openHomes(p2.player(), p2.page()));
    }

    void openWarps(Player player, int page) {
        List<Entry> entries = new ArrayList<>();
        for (String name : module.warps().all().keySet()) {
            if (module.canUseWarp(player, name)) {
                entries.add(warpEntry(name));
            }
        }
        boolean canAdd = player.hasPermission("nucleus.setwarp");
        openList(player, plugin.messages().get("gui.warps-title"), entries, page,
                canAdd ? newButton("gui.new-warp", ItemType.ENDER_PEARL) : null,
                canAdd ? p -> askNewName(p, "gui.new-warp", name -> {
                    if (RESERVED_WARPS.contains(name.toLowerCase(Locale.ROOT))) {
                        throw new CommandFail("warp.invalid-name");
                    }
                    module.warps().set(name, NamedLocation.of(p.getLocation()));
                    plugin.messages().send(p, "warp.set", "name", name);
                    openWarps(p, page);
                }) : null,
                p2 -> openWarps(p2.player(), p2.page()));
    }

    private record PageRequest(Player player, int page) {
    }

    private void openList(Player player, Component title, List<Entry> entries, int page,
                          @Nullable ItemStack addButton, @Nullable Consumer<Player> addAction,
                          Consumer<PageRequest> reopen) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.clamp(page, 0, pages - 1);
        int shown = Math.min(PAGE_SIZE, entries.size() - current * PAGE_SIZE);
        int rows = Math.max(2, (shown + 8) / 9 + 1);
        Menu menu = new Menu(rows, title);
        int bar = (rows - 1) * 9;

        for (int i = 0; i < shown; i++) {
            Entry e = entries.get(current * PAGE_SIZE + i);
            menu.set(i, entryItem(player, e), (p, click) -> {
                if (click.isRightClick() && e.canEdit(p)) {
                    openEdit(p, e);
                } else if (click.isLeftClick()) {
                    p.closeInventory();
                    e.teleport(p);
                }
            });
        }
        if (entries.isEmpty()) {
            menu.set(4, Icons.icon(ItemType.BARRIER, plugin.messages().get("gui.empty"), List.of()), null);
        }
        if (current > 0) {
            menu.set(bar, Icons.icon(ItemType.ARROW, plugin.messages().get("gui.previous"), List.of()),
                    (p, c) -> reopen.accept(new PageRequest(p, current - 1)));
        }
        if (current < pages - 1) {
            menu.set(bar + 8, Icons.icon(ItemType.ARROW, plugin.messages().get("gui.next"), List.of()),
                    (p, c) -> reopen.accept(new PageRequest(p, current + 1)));
        }
        if (pages > 1) {
            menu.set(bar + 3, Icons.icon(ItemType.PAPER,
                    plugin.messages().get("gui.page", "page", current + 1, "pages", pages), List.of()), null);
        }
        if (addButton != null && addAction != null) {
            menu.set(bar + 4, addButton, (p, c) -> addAction.accept(p));
        }
        menu.open(player);
    }

    private ItemStack entryItem(Player viewer, Entry e) {
        NamedLocation loc = e.location();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("gui.entry-location", "world", loc.world(),
                "x", Formats.coord(loc.x()), "y", Formats.coord(loc.y()), "z", Formats.coord(loc.z())));
        lore.add(plugin.messages().get("gui.entry-left"));
        if (e.canEdit(viewer)) {
            lore.add(plugin.messages().get("gui.entry-right"));
        }
        return Icons.icon(e.icon(), e.defaultIcon(), plugin.messages().get("gui.entry-name", "name", e.name()), lore);
    }

    private ItemStack newButton(String key, ItemType type) {
        return Icons.icon(type, plugin.messages().get(key), List.of(plugin.messages().get("gui.new-lore")));
    }

    // --- Bearbeiten ---

    private void openEdit(Player player, Entry e) {
        Menu menu = new Menu(3, plugin.messages().get("gui.edit-title", "name", e.name()));
        menu.set(11, Icons.icon(ItemType.NAME_TAG, plugin.messages().get("gui.rename"),
                List.of(plugin.messages().get("gui.rename-lore"))), (p, c) -> askRename(p, e));
        menu.set(13, Icons.icon(e.icon(), e.defaultIcon(), plugin.messages().get("gui.icon"),
                plugin.messages().lines("gui.icon-lore")), (p, c) -> openIconPicker(p, e));
        if (e.canDelete(player)) {
            menu.set(15, Icons.icon(ItemType.BARRIER, plugin.messages().get("gui.delete"),
                    List.of(plugin.messages().get("gui.delete-lore"))), (p, c) -> {
                p.closeInventory();
                Dialogs.confirm(plugin, p, plugin.messages().get("gui.delete-confirm", "name", e.name()), () -> {
                    e.delete();
                    plugin.messages().send(p, "gui.deleted", "name", e.name());
                    e.backToList(p);
                });
            });
        }
        menu.set(18, Icons.icon(ItemType.ARROW, plugin.messages().get("gui.back"), List.of()),
                (p, c) -> e.backToList(p));
        // Symbol wählen: ein Item im eigenen Inventar anklicken
        menu.onInventoryPick((p, item) -> chooseIcon(p, e, item));
        menu.open(player);
    }

    /** Auswahl an Symbolen aus der config.yml (gui.icons), dazu weiterhin ein Item aus dem Inventar. */
    private void openIconPicker(Player player, Entry e) {
        Menu menu = new Menu(6, plugin.messages().get("gui.icon-picker-title", "name", e.name()));
        int slot = 0;
        for (String key : plugin.config("teleport").getStringList("gui.icons")) {
            ItemType type = Icons.type(key);
            if (type == null || type == ItemType.AIR || slot >= PAGE_SIZE) {
                continue; // unbekannte Einträge überspringen
            }
            ItemStack choice = type.createItemStack(1);
            menu.set(slot++, Icons.icon(type, choice.effectiveName(), plugin.messages().lines("gui.icon-choice-lore")),
                    (p, c) -> chooseIcon(p, e, choice));
        }
        menu.set(45, Icons.icon(ItemType.ARROW, plugin.messages().get("gui.back"), List.of()), (p, c) -> openEdit(p, e));
        menu.set(49, Icons.icon(e.icon(), e.defaultIcon(), plugin.messages().get("gui.icon-current"),
                plugin.messages().lines("gui.icon-picker-hint")), null);
        menu.onInventoryPick((p, item) -> chooseIcon(p, e, item));
        menu.open(player);
    }

    private void chooseIcon(Player player, Entry e, ItemStack item) {
        e.icon(Icons.key(item));
        plugin.messages().send(player, "gui.icon-set", "name", e.name(), "item", item.effectiveName());
        openEdit(player, e);
    }

    private void askRename(Player player, Entry e) {
        player.closeInventory();
        Dialogs.askText(plugin, player, plugin.messages().get("gui.rename-title", "name", e.name()),
                plugin.messages().get("gui.name-label"), e.name(), 32, input -> safely(player, () -> {
                    if (!TeleportModule.NAME.matcher(input).matches()) {
                        throw new CommandFail("home.invalid-name");
                    }
                    String old = e.name(); // vor dem Umbenennen merken, danach liefert e.name() den neuen
                    if (!e.rename(input)) {
                        throw new CommandFail("gui.name-taken", "name", input);
                    }
                    plugin.messages().send(player, "gui.renamed", "old", old, "name", input);
                    e.backToList(player);
                }));
    }

    private void askNewName(Player player, String titleKey, Consumer<String> create) {
        player.closeInventory();
        Dialogs.askText(plugin, player, plugin.messages().get(titleKey), plugin.messages().get("gui.name-label"),
                "", 32, input -> safely(player, () -> {
                    if (!TeleportModule.NAME.matcher(input).matches()) {
                        throw new CommandFail("home.invalid-name");
                    }
                    create.accept(input);
                }));
    }

    /** Fehler aus Dialog-Aktionen als Nachricht anzeigen (außerhalb von Befehlen gibt es keinen CommandKit). */
    private void safely(Player player, Runnable action) {
        try {
            action.run();
        } catch (CommandFail fail) {
            plugin.messages().send(player, fail.key(), fail.placeholders());
        }
    }

    // --- Einträge ---

    private Entry homeEntry(Player owner, String name) {
        return new Entry() {
            private String current = name;

            private PlayerData data() {
                return plugin.playerData().get(owner);
            }

            @Override
            public String name() {
                return current;
            }

            @Override
            public @Nullable String icon() {
                return data().homeIcon(current);
            }

            @Override
            public NamedLocation location() {
                NamedLocation loc = data().home(current);
                return loc != null ? loc : NamedLocation.of(owner.getLocation());
            }

            @Override
            public ItemType defaultIcon() {
                return ItemType.RED_BED;
            }

            @Override
            public boolean rename(String newName) {
                boolean ok = data().renameHome(current, newName);
                if (ok) {
                    current = newName.toLowerCase(Locale.ROOT);
                }
                return ok;
            }

            @Override
            public void icon(String itemKey) {
                data().homeIcon(current, itemKey);
            }

            @Override
            public void delete() {
                data().removeHome(current);
            }

            @Override
            public boolean canEdit(Player player) {
                return player.hasPermission("nucleus.sethome");
            }

            @Override
            public boolean canDelete(Player player) {
                return player.hasPermission("nucleus.delhome");
            }

            @Override
            public void teleport(Player player) {
                Location target = location().toLocation();
                if (target == null) {
                    plugin.messages().send(player, "teleport.world-missing", "world", location().world());
                    return;
                }
                plugin.teleports().teleport(player, target, Options.player("home"),
                        () -> plugin.messages().send(player, "home.teleported", "name", current));
            }

            @Override
            public void backToList(Player player) {
                openHomes(player, 0);
            }
        };
    }

    private Entry warpEntry(String name) {
        return new Entry() {
            private String current = name;

            @Override
            public String name() {
                return current;
            }

            @Override
            public @Nullable String icon() {
                return module.warps().icon(current);
            }

            @Override
            public NamedLocation location() {
                NamedLocation loc = module.warps().get(current);
                return loc != null ? loc : new NamedLocation("?", 0, 0, 0, 0, 0);
            }

            @Override
            public ItemType defaultIcon() {
                return ItemType.ENDER_PEARL;
            }

            @Override
            public boolean rename(String newName) {
                if (RESERVED_WARPS.contains(newName.toLowerCase(Locale.ROOT))) {
                    return false;
                }
                boolean ok = module.warps().rename(current, newName);
                if (ok) {
                    current = newName.toLowerCase(Locale.ROOT);
                }
                return ok;
            }

            @Override
            public void icon(String itemKey) {
                module.warps().icon(current, itemKey);
            }

            @Override
            public void delete() {
                module.warps().remove(current);
            }

            @Override
            public boolean canEdit(Player player) {
                return player.hasPermission("nucleus.setwarp");
            }

            @Override
            public boolean canDelete(Player player) {
                return player.hasPermission("nucleus.delwarp");
            }

            @Override
            public void teleport(Player player) {
                Location target = location().toLocation();
                if (target == null) {
                    plugin.messages().send(player, "teleport.world-missing", "world", location().world());
                    return;
                }
                plugin.teleports().teleport(player, target, Options.player("warp"),
                        () -> plugin.messages().send(player, "warp.teleported", "name", current));
            }

            @Override
            public void backToList(Player player) {
                openWarps(player, 0);
            }
        };
    }
}
