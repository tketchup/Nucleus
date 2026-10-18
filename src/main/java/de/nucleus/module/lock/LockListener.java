package de.nucleus.module.lock;

import de.nucleus.NucleusPlugin;
import de.nucleus.gui.Dialogs;
import de.nucleus.util.Passwords;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

import java.util.HashSet;
import java.util.List;

/** Durchsetzung der Sperren: Öffnen, Abbauen, Doppeltruhen, Trichter, Explosionen. */
final class LockListener implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final NucleusPlugin plugin;
    private final LockModule module;

    LockListener(NucleusPlugin plugin, LockModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** Öffnen: Fremde werden abgewiesen; ist ein Passwort gesetzt, fragt ein Dialog danach. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        LockService.Lock lock = module.locks().get(block);
        Player player = event.getPlayer();
        if (lock == null || module.locks().canAccess(player, block, lock)) {
            return;
        }
        event.setCancelled(true);
        String owner = ownerName(lock);
        if (lock.hasPassword() && module.settings().passwordsEnabled()) {
            askPassword(player, block, lock, owner);
        } else {
            plugin.messages().send(player, "lock.locked", "player", owner);
        }
    }

    private void askPassword(Player player, Block block, LockService.Lock lock, String owner) {
        Dialogs.askText(plugin, player, plugin.messages().get("lock.password-prompt-title", "player", owner),
                plugin.messages().get("lock.password-label"), "", 64, input -> {
                    // Sperre könnte sich inzwischen geändert haben: neu lesen
                    LockService.Lock current = module.locks().get(block);
                    if (current == null || !current.hasPassword()) {
                        plugin.messages().send(player, "lock.password-wrong");
                        return;
                    }
                    String hash = current.passwordHash();
                    module.offThread(() -> Passwords.verify(input, hash), ok -> {
                        if (!ok) {
                            plugin.messages().send(player, "lock.password-wrong");
                            return;
                        }
                        grantAccess(player, block);
                    });
                });
    }

    /** Richtiges Passwort: Zugang für diese Sitzung (oder dauerhaft als Vertrauter) und Behälter öffnen. */
    private void grantAccess(Player player, Block block) {
        LockService.Lock now = module.locks().get(block);
        if (now == null || !player.isOnline()) {
            return;
        }
        if (module.settings().rememberPermanently()) {
            var trusted = new HashSet<>(now.trusted());
            trusted.add(player.getUniqueId());
            module.locks().set(block, new LockService.Lock(now.owner(), trusted, now.passwordHash()));
        } else {
            module.locks().grantSession(player, block);
        }
        plugin.messages().send(player, "lock.password-correct");
        if (block.getState(false) instanceof Container container
                && player.getLocation().distanceSquared(block.getLocation()) < 64) {
            player.openInventory(container.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        LockService.Lock lock = module.locks().get(event.getBlock());
        Player player = event.getPlayer();
        if (lock != null && !lock.owner().equals(player.getUniqueId()) && !player.hasPermission("nucleus.lock.bypass")) {
            event.setCancelled(true);
            plugin.messages().send(player, "lock.locked", "player", ownerName(lock));
        }
    }

    /**
     * Keine fremde Truhe zur Doppeltruhe erweitern (sonst käme man an den Inhalt). Eigene Truhen
     * übernehmen die Sperre; mit auto-lock wird jeder neue Behälter sofort gesperrt.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block placed = event.getBlockPlaced();
        for (BlockFace face : SIDES) {
            LockService.Lock lock = module.locks().get(placed.getRelative(face));
            if (lock != null && !lock.owner().equals(player.getUniqueId()) && !player.hasPermission("nucleus.lock.bypass")
                    && LockService.typeKey(placed).equals(LockService.typeKey(placed.getRelative(face)))) {
                event.setCancelled(true);
                plugin.messages().send(player, "lock.no-merge", "player", ownerName(lock));
                return;
            }
        }
        // Einen Tick später: erst dann ist eine Doppeltruhe zusammengewachsen
        Bukkit.getScheduler().runTask(plugin, () -> afterPlace(player, placed));
    }

    private void afterPlace(Player player, Block placed) {
        if (!module.isLockable(placed) || module.locks().get(placed) != null) {
            return;
        }
        for (Block part : module.locks().parts(placed)) {
            LockService.Lock other = part.equals(placed) ? null : module.locks().get(part);
            if (other != null) {
                module.locks().set(placed, other); // Doppeltruhe: Sperre der anderen Hälfte übernehmen
                return;
            }
        }
        if (module.settings().autoLock() && player.isOnline() && player.hasPermission("nucleus.lock")) {
            module.locks().set(placed, new LockService.Lock(player.getUniqueId(), new HashSet<>(), null));
            plugin.messages().send(player, "lock.auto-locked");
        }
    }

    /** Trichter dürfen nur aus gesperrten Behältern ziehen, wenn sie demselben Besitzer gehören. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!module.settings().protectHoppers()) {
            return;
        }
        LockService.Lock source = lockOf(event.getSource());
        if (source == null) {
            return;
        }
        LockService.Lock target = lockOf(event.getDestination());
        if (target == null || !target.owner().equals(source.owner())) {
            event.setCancelled(true);
        }
    }

    private LockService.Lock lockOf(Inventory inventory) {
        Block block = inventory.getHolder(false) instanceof BlockInventoryHolder holder ? holder.getBlock() : null;
        if (block == null && inventory.getLocation() != null && inventory.getHolder(false) instanceof org.bukkit.block.DoubleChest) {
            block = inventory.getLocation().getBlock();
        }
        return block == null ? null : module.locks().get(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    private void protect(List<Block> blocks) {
        if (module.settings().protectExplosions()) {
            blocks.removeIf(b -> module.locks().get(b) != null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        module.locks().forget(event.getPlayer().getUniqueId());
    }

    private String ownerName(LockService.Lock lock) {
        String name = Bukkit.getOfflinePlayer(lock.owner()).getName();
        return name != null ? name : "?";
    }
}
