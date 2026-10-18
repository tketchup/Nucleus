package de.nucleus.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Eine Position mit Weltname statt Weltobjekt. Bleibt gültig, auch wenn die Welt gerade nicht
 * geladen ist, und lässt sich ohne Server-Zugriff speichern.
 */
public record NamedLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static NamedLocation of(Location loc) {
        return new NamedLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    /** @return die Location, oder null wenn die Welt nicht (mehr) existiert */
    public @Nullable Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", (double) yaw);
        section.set("pitch", (double) pitch);
    }

    public static @Nullable NamedLocation read(@Nullable ConfigurationSection section) {
        if (section == null || !section.isString("world")) {
            return null;
        }
        return new NamedLocation(section.getString("world"), section.getDouble("x"), section.getDouble("y"),
                section.getDouble("z"), (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }
}
