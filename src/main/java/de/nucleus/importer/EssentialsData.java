package de.nucleus.importer;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Liest das Datenformat von EssentialsX (geladene YAML-Dateien als Maps). Eigene Umsetzung nach dem
 * Dateiformat, kein Code von EssentialsX. Ohne Bukkit-Abhängigkeit, damit testbar.
 *
 * <p>Orte stehen bei EssentialsX als {@code world} (Welt-UUID), {@code world-name}, {@code x}, {@code y},
 * {@code z}, {@code yaw}, {@code pitch}. Mehrteilige Schlüssel sind klein mit Bindestrich geschrieben
 * (z. B. {@code last-account-name}); ältere Versionen nutzten teils andere Schreibweisen, daher
 * werden mehrere Varianten akzeptiert.
 */
public final class EssentialsData {

    /** Ein Ort; world ist meist eine UUID, worldName der lesbare Name. */
    public record Loc(String world, String worldName, double x, double y, double z, float yaw, float pitch) {
    }

    /** Die für Nucleus relevanten Daten eines Spielers. */
    public record User(UUID uuid, @Nullable String name, @Nullable BigDecimal money, Map<String, Loc> homes,
                       @Nullable String nickname, @Nullable Loc lastLocation, List<UUID> ignored,
                       boolean muted, long muteUntil, @Nullable String muteReason,
                       boolean socialSpy, boolean teleportEnabled, long lastLogout) {
    }

    private EssentialsData() {
    }

    public static User parseUser(UUID uuid, Map<?, ?> yaml) {
        Map<String, Loc> homes = new LinkedHashMap<>();
        if (yaml.get("homes") instanceof Map<?, ?> homeMap) {
            homeMap.forEach((name, value) -> {
                Loc loc = parseLoc(value);
                if (loc != null) {
                    homes.put(String.valueOf(name), loc);
                }
            });
        }
        List<UUID> ignored = new ArrayList<>();
        if (yaml.get("ignore") instanceof List<?> list) {
            for (Object o : list) {
                UUID id = uuid(o);
                if (id != null) {
                    ignored.add(id);
                }
            }
        }
        Map<?, ?> timestamps = yaml.get("timestamps") instanceof Map<?, ?> t ? t : Map.of();
        return new User(uuid,
                string(yaml, "last-account-name", "lastAccountName", "lastaccountname"),
                money(yaml.get("money")),
                homes,
                string(yaml, "nickname"),
                parseLoc(first(yaml, "lastlocation", "last-location")),
                ignored,
                bool(yaml, false, "muted"),
                number(timestamps.get("mute")),
                string(yaml, "mute-reason", "muteReason", "mutereason"),
                bool(yaml, false, "socialspy", "social-spy"),
                bool(yaml, true, "teleportenabled", "teleport-enabled"),
                number(timestamps.get("logout")));
    }

    /** Ort aus einer Map; null, wenn Welt oder Koordinaten fehlen. */
    public static @Nullable Loc parseLoc(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map) || map.get("world") == null) {
            return null;
        }
        if (!(map.get("x") instanceof Number) || !(map.get("y") instanceof Number) || !(map.get("z") instanceof Number)) {
            return null;
        }
        Object worldName = map.get("world-name");
        return new Loc(String.valueOf(map.get("world")), worldName == null ? "" : String.valueOf(worldName),
                ((Number) map.get("x")).doubleValue(), ((Number) map.get("y")).doubleValue(), ((Number) map.get("z")).doubleValue(),
                map.get("yaw") instanceof Number n ? n.floatValue() : 0f,
                map.get("pitch") instanceof Number n ? n.floatValue() : 0f);
    }

    /** Abschnitt mit benannten Orten, z. B. "jails" in jail.yml oder "spawns" in spawn.yml. */
    public static Map<String, Loc> parseLocationSection(@Nullable Object section) {
        Map<String, Loc> out = new LinkedHashMap<>();
        if (section instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                Loc loc = parseLoc(value);
                if (loc != null) {
                    out.put(String.valueOf(name), loc);
                }
            });
        }
        return out;
    }

    /** Warp-Datei: der Ort steht auf oberster Ebene, der Name unter "name" (sonst Dateiname). */
    public static Map.@Nullable Entry<String, Loc> parseWarp(String fileName, Map<?, ?> yaml) {
        Loc loc = parseLoc(yaml);
        if (loc == null) {
            return null;
        }
        String name = yaml.get("name") instanceof String s && !s.isBlank() ? s : fileName;
        return Map.entry(name, loc);
    }

    /**
     * Namen für Nucleus passend machen: erlaubt sind Buchstaben, Ziffern, _ und -, höchstens 32 Zeichen.
     * Alles andere wird zu "_". Leere Namen ergeben null.
     */
    public static @Nullable String cleanName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.length() > 32) {
            clean = clean.substring(0, 32);
        }
        return clean.isBlank() || clean.chars().allMatch(c -> c == '_') ? null : clean;
    }

    // --- Hilfen ---

    private static @Nullable Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static @Nullable String string(Map<?, ?> map, String... keys) {
        Object value = first(map, keys);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, boolean fallback, String... keys) {
        Object value = first(map, keys);
        return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static long number(@Nullable Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Geld steht als Zahl oder als Text ('123.45'); nie über double umrechnen. */
    private static @Nullable BigDecimal money(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(String.valueOf(value).trim());
            return d.signum() < 0 ? BigDecimal.ZERO : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
