package de.nucleus.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Ein Kisten-Menü mit Klick-Aktionen pro Slot. Erkennt wird es über den {@link InventoryHolder};
 * {@link MenuListener} bricht alle Klicks ab und ruft stattdessen die Aktionen auf. Spieler können
 * also nie Items herausnehmen.
 */
public final class Menu implements InventoryHolder {

    /** Aktion für einen Klick auf einen Slot im Menü. */
    @FunctionalInterface
    public interface ClickAction {
        void click(Player player, ClickType type);
    }

    /** Aktion für einen Klick ins eigene Inventar, während das Menü offen ist (z. B. Symbol wählen). */
    @FunctionalInterface
    public interface InventoryPick {
        void pick(Player player, ItemStack item);
    }

    private final Inventory inventory;
    private final Map<Integer, ClickAction> actions = new HashMap<>();
    private @Nullable InventoryPick pick;

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.clamp(rows, 1, 6) * 9, title);
    }

    public void set(int slot, ItemStack item, @Nullable ClickAction action) {
        inventory.setItem(slot, item);
        if (action == null) {
            actions.remove(slot);
        } else {
            actions.put(slot, action);
        }
    }

    public void onInventoryPick(InventoryPick action) {
        this.pick = action;
    }

    public int size() {
        return inventory.getSize();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void handleClick(Player player, int slot, ClickType type) {
        ClickAction action = actions.get(slot);
        if (action != null) {
            action.click(player, type);
        }
    }

    void handlePick(Player player, ItemStack item) {
        if (pick != null && item != null && !item.isEmpty()) {
            pick.pick(player, item);
        }
    }
}
