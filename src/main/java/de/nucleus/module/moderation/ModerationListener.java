package de.nucleus.module.moderation;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.PlayerData;
import de.nucleus.util.TimeParser;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;

/**
 * Durchsetzung: eigener Ban-Bildschirm beim Login, Mute (Chat und Befehle, auch ohne chat-Modul),
 * Freeze, Jail, CommandSpy und nur-lesbare Inventaransichten.
 */
final class ModerationListener implements Listener {

    private final NucleusPlugin plugin;
    private final ModerationModule module;

    ModerationListener(NucleusPlugin plugin, ModerationModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    // --- Bans: eigener, deutscher Bildschirm statt der Vanilla-Meldung ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        BanEntry<?> entry = Bukkit.getBanList(BanListType.PROFILE).getBanEntry(event.getPlayerProfile());
        if (entry == null) {
            InetAddress address = event.getAddress();
            entry = Bukkit.getBanList(BanListType.IP).getBanEntry(address);
        }
        if (entry != null && (entry.getExpiration() == null || entry.getExpiration().after(new Date()))) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(plugin, entry.getReason(),
                    entry.getExpiration(), entry.getSource()));
        }
    }

    static Component banScreen(NucleusPlugin plugin, String reason, Date expires, String source) {
        String until = expires == null ? plugin.messages().raw("moderation.permanent")
                : plugin.messages().duration(Duration.between(java.time.Instant.now(), expires.toInstant()));
        return plugin.messages().get(expires == null ? "moderation.ban-screen" : "moderation.tempban-screen",
                "reason", reason == null ? "" : reason, "time", until, "source", source == null ? "" : source);
    }

    // --- Mute ---

    /** LOWEST: vor jedem Chat-Plugin (auch TagMC) abbrechen. Läuft asynchron. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PlayerData data = plugin.playerData().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null && data.isMuted()) {
            event.setCancelled(true);
            sendMuted(event.getPlayer(), data);
        }
    }

    private void sendMuted(Player player, PlayerData data) {
        String time = data.muteUntil() == Long.MAX_VALUE ? plugin.messages().raw("moderation.permanent")
                : plugin.messages().duration(Duration.ofMillis(data.muteUntil() - System.currentTimeMillis()));
        plugin.messages().send(player, "moderation.you-are-muted", "time", time, "reason", data.muteReason());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        if (data == null) {
            return;
        }
        String label = label(event.getMessage());
        if (data.isMuted() && module.settings().muteBlockedCommands().contains(label)) {
            event.setCancelled(true);
            sendMuted(player, data);
            return;
        }
        if (data.jail() != null && !module.settings().jailAllowedCommands().contains(label)
                && !player.hasPermission("nucleus.jail.exempt")) {
            event.setCancelled(true);
            plugin.messages().send(player, "moderation.jail-no-commands");
        }
    }

    /** "/minecraft:msg Bob hi" → "msg" */
    static String label(String message) {
        String first = message.startsWith("/") ? message.substring(1) : message;
        int space = first.indexOf(' ');
        if (space >= 0) {
            first = first.substring(0, space);
        }
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        return first.toLowerCase(Locale.ROOT);
    }

    // --- CommandSpy ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandSpy(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (sender.hasPermission("nucleus.commandspy.exempt")) {
            return;
        }
        Component line = null;
        for (Player spy : Bukkit.getOnlinePlayers()) {
            if (spy.equals(sender) || !spy.hasPermission("nucleus.commandspy")) {
                continue;
            }
            PlayerData d = plugin.playerData().getIfLoaded(spy.getUniqueId());
            if (d != null && d.commandSpy()) {
                if (line == null) {
                    line = plugin.messages().get("moderation.commandspy-line", "player", sender.getName(),
                            "command", event.getMessage());
                }
                spy.sendMessage(line);
            }
        }
    }

    // --- Freeze und Jail ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (data.frozen()) {
            // Umschauen erlaubt, Bewegung nicht
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
            return;
        }
        if (data.jail() != null && event.hasChangedBlock()) {
            Location jail = module.jailService().jailLocation(data);
            if (jail != null && (!jail.getWorld().equals(event.getTo().getWorld())
                    || jail.distanceSquared(event.getTo()) > (double) module.settings().jailRadius() * module.settings().jailRadius())) {
                event.setTo(jail);
                plugin.messages().send(player, "moderation.jail-leave");
            }
        }
    }

    private boolean restricted(Player player) {
        PlayerData data = plugin.playerData().getIfLoaded(player.getUniqueId());
        return data != null && (data.frozen() || data.jail() != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        PlayerData data = plugin.playerData().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null && data.frozen()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.playerData().getIfLoaded(event.getPlayer().getUniqueId());
        Location jail = data == null ? null : module.jailService().jailLocation(data);
        if (jail != null) {
            event.setRespawnLocation(jail);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.playerData().get(player);
        if (data.jail() != null) {
            // einen Tick später: Teleports direkt im Join-Event sind unzuverlässig
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || data.jail() == null) {
                    return;
                }
                if (data.jailUntil() <= System.currentTimeMillis()) {
                    module.jailService().release(player, data, true);
                } else {
                    module.jailService().sendToJail(player, data);
                    plugin.messages().send(player, "moderation.still-jailed");
                }
            });
        }
        if (data.frozen()) {
            plugin.messages().send(player, "moderation.frozen-target");
        }
    }

    // --- Nur-lesbare Inventaransichten (/invsee, /enderchest ohne modify-Recht) ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (InventoryCommands.isReadOnly(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (InventoryCommands.isReadOnly(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryCommands.closed(event.getPlayer().getUniqueId());
    }
}
