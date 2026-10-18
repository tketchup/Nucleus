package de.nucleus.module.gameplay;

import de.nucleus.NucleusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** God-Modus, Fly/God-Wiederherstellung und Powertools. */
final class GameplayListener implements Listener {

    private final NucleusPlugin plugin;
    private final GameplayModule module;

    GameplayListener(NucleusPlugin plugin, GameplayModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && module.isGod(p)) {
            event.setCancelled(true);
            p.setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (module.settings().godNoHunger() && event.getEntity() instanceof Player p && module.isGod(p)
                && event.getFoodLevel() < p.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        module.restore(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        module.forget(event.getPlayer());
    }

    /** Vanilla nimmt beim Welt- oder Moduswechsel das Fliegen weg: einen Tick später wiederherstellen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                module.restore(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                module.restore(p);
            }
        });
    }

    /** Powertool: Linksklick mit dem Item führt den gebundenen Befehl aus. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty() || !player.hasPermission("nucleus.powertool")) {
            return;
        }
        var data = plugin.playerData().getIfLoaded(player.getUniqueId());
        String command = data == null ? null : data.powertools().get(de.nucleus.gui.Icons.key(hand));
        if (command != null) {
            event.setCancelled(true);
            player.performCommand(command);
        }
    }
}
