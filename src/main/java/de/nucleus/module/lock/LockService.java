package de.nucleus.module.lock;

import de.nucleus.NucleusPlugin;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sperren werden direkt im Block gespeichert (PersistentDataContainer des Tile-Entitys). Sie
 * wandern also mit der Welt, brauchen keine eigene Datei und verschwinden, wenn der Block abgebaut wird.
 * Doppeltruhen bekommen die Sperre auf beiden Hälften. Nur Hauptthread.
 */
final class LockService {

    /** Eine Sperre: Besitzer, vertraute Spieler, optional ein Passwort-Hash. */
    record Lock(UUID owner, Set<UUID> trusted, @Nullable String passwordHash) {
        boolean hasPassword() {
            return passwordHash != null && !passwordHash.isEmpty();
        }
    }

    private final NamespacedKey ownerKey;
    private final NamespacedKey trustedKey;
    private final NamespacedKey passwordKey;
    /** Spieler → Blöcke, die er in dieser Sitzung per Passwort öffnen darf. */
    private final Map<UUID, Set<String>> sessionAccess = new HashMap<>();

    LockService(NucleusPlugin plugin) {
        this.ownerKey = new NamespacedKey(plugin, "lock_owner");
        this.trustedKey = new NamespacedKey(plugin, "lock_trusted");
        this.passwordKey = new NamespacedKey(plugin, "lock_password");
    }

    /** Liest die Sperre ohne Kopie des Blockzustands (schnell, für jeden Klick). */
    @Nullable Lock get(Block block) {
        if (!(block.getState(false) instanceof TileState tile)) {
            return null;
        }
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (owner == null) {
            return null;
        }
        try {
            String trusted = pdc.getOrDefault(trustedKey, PersistentDataType.STRING, "");
            Set<UUID> ids = trusted.isEmpty() ? new LinkedHashSet<>()
                    : Arrays.stream(trusted.split(",")).map(UUID::fromString).collect(Collectors.toCollection(LinkedHashSet::new));
            return new Lock(UUID.fromString(owner), ids, pdc.get(passwordKey, PersistentDataType.STRING));
        } catch (IllegalArgumentException e) {
            return null; // kaputte Daten: lieber offen als für immer gesperrt
        }
    }

    /** Schreibt die Sperre (auf beide Hälften einer Doppeltruhe). */
    void set(Block block, Lock lock) {
        for (Block part : parts(block)) {
            if (part.getState() instanceof TileState tile) {
                PersistentDataContainer pdc = tile.getPersistentDataContainer();
                pdc.set(ownerKey, PersistentDataType.STRING, lock.owner().toString());
                pdc.set(trustedKey, PersistentDataType.STRING,
                        lock.trusted().stream().map(UUID::toString).collect(Collectors.joining(",")));
                if (lock.hasPassword()) {
                    pdc.set(passwordKey, PersistentDataType.STRING, lock.passwordHash());
                } else {
                    pdc.remove(passwordKey);
                }
                tile.update();
            }
        }
    }

    void remove(Block block) {
        for (Block part : parts(block)) {
            if (part.getState() instanceof TileState tile) {
                PersistentDataContainer pdc = tile.getPersistentDataContainer();
                pdc.remove(ownerKey);
                pdc.remove(trustedKey);
                pdc.remove(passwordKey);
                tile.update();
            }
        }
    }

    /** Der Block selbst, bei Doppeltruhen beide Hälften. */
    List<Block> parts(Block block) {
        List<Block> parts = new ArrayList<>(2);
        BlockState state = block.getState(false);
        if (state instanceof Chest chest && chest.getInventory() instanceof DoubleChestInventory dci) {
            for (Location loc : new Location[]{dci.getLeftSide().getLocation(), dci.getRightSide().getLocation()}) {
                if (loc != null) {
                    parts.add(loc.getBlock());
                }
            }
        }
        if (parts.isEmpty()) {
            parts.add(block);
        }
        return parts;
    }

    /** Besitzer, Vertraute, Bypass-Recht oder per Passwort in dieser Sitzung freigeschaltet. */
    boolean canAccess(Player player, Block block, Lock lock) {
        UUID id = player.getUniqueId();
        if (lock.owner().equals(id) || lock.trusted().contains(id) || player.hasPermission("nucleus.lock.bypass")) {
            return true;
        }
        Set<String> access = sessionAccess.get(id);
        return access != null && parts(block).stream().anyMatch(b -> access.contains(key(b)));
    }

    void grantSession(Player player, Block block) {
        Set<String> access = sessionAccess.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        parts(block).forEach(b -> access.add(key(b)));
    }

    void forget(UUID player) {
        sessionAccess.remove(player);
    }

    void clearSessions() {
        sessionAccess.clear();
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    /** Block-Schlüssel wie "minecraft:chest" zum Abgleich mit der Konfiguration. */
    @SuppressWarnings("deprecation") // Block#getType liefert in 26.3 noch Material; asBlockType() führt zum BlockType
    static String typeKey(Block block) {
        var type = block.getType().asBlockType();
        return type == null ? "minecraft:air" : type.key().asString();
    }
}
