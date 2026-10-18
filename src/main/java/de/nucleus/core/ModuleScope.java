package de.nucleus.core;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Merkt sich die Listener und Tasks eines Moduls, damit {@link #close()} beim Deaktivieren alles
 * zuverlässig wieder entfernt.
 */
public final class ModuleScope {

    private final Plugin plugin;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    public ModuleScope(Plugin plugin) {
        this.plugin = plugin;
    }

    public void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** Wiederholender Task auf dem Hauptthread. */
    public void timer(Runnable task, long delayTicks, long periodTicks) {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    public void close() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
