package de.nucleus.util;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cooldowns pro Spieler und Schlüssel (z. B. "home", "tpa"). Nur im Speicher, nur Hauptthread.
 */
public final class CooldownManager {

    private final Map<UUID, Map<String, Long>> expiries = new HashMap<>();

    /** @return verbleibende Zeit, {@link Duration#ZERO} wenn kein Cooldown läuft */
    public Duration remaining(UUID player, String key) {
        Map<String, Long> map = expiries.get(player);
        if (map == null) {
            return Duration.ZERO;
        }
        Long until = map.get(key);
        long left = until == null ? 0 : until - System.currentTimeMillis();
        if (left <= 0) {
            map.remove(key);
            return Duration.ZERO;
        }
        return Duration.ofMillis(left);
    }

    public void set(UUID player, String key, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        expiries.computeIfAbsent(player, k -> new HashMap<>())
                .put(key, System.currentTimeMillis() + duration.toMillis());
    }

    public void clear(UUID player) {
        expiries.remove(player);
    }
}
