package de.nucleus.core;

import de.nucleus.NucleusPlugin;
import io.papermc.paper.command.brigadier.Commands;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Lädt und entlädt Module nach {@code modules.<id>} in der config.yml.
 *
 * <p>Module sind nur als Klassenname bekannt. Ein abgeschaltetes Modul wird nie geladen: keine Klasse,
 * keine Listener, keine Befehle.
 *
 * <p>Befehle: Brigadier-Befehle lassen sich zur Laufzeit nicht offiziell hinzufügen oder entfernen.
 * Paper fragt sie beim Start und bei jedem /minecraft:reload neu ab. Wird ein Modul per /nucleus reload
 * abgeschaltet, sind seine Befehle sofort gesperrt (requires-Prüfung) und verschwinden beim nächsten
 * Neustart ganz. Ein neu eingeschaltetes Modul bekommt seine Befehle erst dann.
 */
public final class ModuleManager {

    /** Reihenfolge = Ladereihenfolge. */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("teleport", "de.nucleus.module.teleport.TeleportModule");
        KNOWN.put("chat", "de.nucleus.module.chat.ChatModule");
        KNOWN.put("info", "de.nucleus.module.info.InfoModule");
        KNOWN.put("economy", "de.nucleus.module.economy.EconomyModule");
        KNOWN.put("moderation", "de.nucleus.module.moderation.ModerationModule");
        KNOWN.put("gameplay", "de.nucleus.module.gameplay.GameplayModule");
        KNOWN.put("lock", "de.nucleus.module.lock.LockModule");
    }

    private final NucleusPlugin plugin;
    private final Map<String, Module> instances = new HashMap<>();
    private final Set<String> active = new LinkedHashSet<>();
    /** Module, deren Befehle gerade im Dispatcher stehen. */
    private final Set<String> withCommands = new LinkedHashSet<>();

    public ModuleManager(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    public Set<String> known() {
        return Collections.unmodifiableSet(KNOWN.keySet());
    }

    public boolean isActive(String id) {
        return active.contains(id);
    }

    public boolean hasCommands(String id) {
        return withCommands.contains(id);
    }

    public boolean isConfigured(String id) {
        return plugin.getConfig().getBoolean("modules." + id);
    }

    /** Liefert ein aktives Modul, z. B. für Abhängigkeiten zwischen Modulen. */
    public <T extends Module> T get(String id, Class<T> type) {
        Module m = active.contains(id) ? instances.get(id) : null;
        return type.isInstance(m) ? type.cast(m) : null;
    }

    /** Einstellungen eines aktiven Moduls neu einlesen (ohne Neustart des Moduls). */
    public void reloadSettings(String id) {
        if (active.contains(id)) {
            instances.get(id).reloadSettings();
        }
    }

    public void enableConfigured() {
        for (String id : KNOWN.keySet()) {
            if (isConfigured(id)) {
                enable(id);
            }
        }
    }

    public void disableAll() {
        // in umgekehrter Reihenfolge
        var ids = new java.util.ArrayList<>(active);
        Collections.reverse(ids);
        ids.forEach(this::disable);
    }

    /**
     * Deaktiviert alle Module und aktiviert sie nach der neuen Config wieder.
     *
     * @return true, wenn sich die Menge der Module mit Befehlen geändert hat (Neustart nötig)
     */
    public boolean reload() {
        disableAll();
        enableConfigured();
        return !active.equals(withCommands);
    }

    /** Vom COMMANDS-Lifecycle-Event aufgerufen. */
    public void registerCommands(Commands registrar) {
        withCommands.clear();
        for (String id : active) {
            try {
                instances.get(id).registerCommands(registrar);
                withCommands.add(id);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Befehle von Modul '" + id + "' konnten nicht registriert werden", e);
            }
        }
    }

    private void enable(String id) {
        try {
            Module module = instances.get(id);
            if (module == null) {
                // Erst hier wird die Klasse geladen
                Class<? extends Module> type = Class.forName(KNOWN.get(id)).asSubclass(Module.class);
                module = type.getConstructor(NucleusPlugin.class).newInstance(plugin);
                instances.put(id, module);
            }
            module.enable();
            active.add(id);
            plugin.getLogger().info("Modul '" + id + "' aktiviert.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Modul '" + id + "' konnte nicht aktiviert werden", e);
            Module broken = instances.get(id);
            if (broken != null) {
                safeDisable(id, broken);
            }
        }
    }

    private void disable(String id) {
        Module module = instances.get(id);
        if (module != null && active.remove(id)) {
            safeDisable(id, module);
            plugin.getLogger().info("Modul '" + id + "' deaktiviert.");
        }
    }

    private void safeDisable(String id, Module module) {
        try {
            module.disable();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Deaktivieren von Modul '" + id + "'", e);
        }
    }
}
