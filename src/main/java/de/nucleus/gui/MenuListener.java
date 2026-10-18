package de.nucleus.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Schützt alle Nucleus-Menüs: jeder Klick wird abgebrochen (keine Items rein oder raus) und an das
 * Menü weitergereicht. Gehört zum Kern und ist immer aktiv.
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        // getHolder(false): kein Snapshot des Blockzustands nötig, schneller
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory().equals(event.getView().getTopInventory())) {
            menu.handleClick(player, event.getSlot(), event.getClick());
        } else {
            menu.handlePick(player, event.getCurrentItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
