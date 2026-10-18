package de.nucleus.addon.pl3xmap;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.NamedLocation;
import de.nucleus.module.teleport.TeleportModule;
import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.event.EventHandler;
import net.pl3x.map.core.event.EventListener;
import net.pl3x.map.core.event.world.WorldLoadedEvent;
import net.pl3x.map.core.event.world.WorldUnloadedEvent;
import net.pl3x.map.core.markers.Point;
import net.pl3x.map.core.markers.marker.Marker;
import net.pl3x.map.core.markers.option.Options;
import net.pl3x.map.core.world.World;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Mob;
import org.bukkit.entity.WaterMob;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Add-on: zeigt Nucleus-Warps, Nucleus-Spawns und Mobs als eigene Ebenen auf der Pl3xMap.
 * Alle Daten werden auf dem Hauptthread gelesen (Nucleus und Bukkit sind dort sicher) und als fertige
 * Marker-Listen an die Ebenen übergeben, die Pl3xMap dann aus seinen eigenen Threads ausliefert.
 */
public final class NucleusPl3xMap extends JavaPlugin implements EventListener {

    private static final String WARPS = "nucleus_warps";
    private static final String SPAWNS = "nucleus_spawns";
    private static final String MOBS = "nucleus_mobs";

    /** Pl3xMap-Weltname → Ebenen dieser Welt. */
    private final Map<String, Map<String, MapLayer>> layers = new ConcurrentHashMap<>();
    /** Kartensymbole aus Minecraft-Texturen, die schon bei Pl3xMap registriert sind bzw. gerade erzeugt werden. */
    private final Set<String> textureIcons = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingIcons = ConcurrentHashMap.newKeySet();
    private NucleusPlugin nucleus;
    private Textures textures;
    private boolean english;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        nucleus = (NucleusPlugin) Bukkit.getPluginManager().getPlugin("Nucleus");
        try {
            de.nucleus.module.teleport.SpawnStore.class.getMethod("all");
        } catch (NoSuchMethodException e) {
            getLogger().severe("Nucleus " + nucleus.getPluginMeta().getVersion()
                    + " ist zu alt, dieses Add-on braucht Nucleus 1.1.1 oder neuer.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        english = "en".equalsIgnoreCase(nucleus.getConfig().getString("language"));

        MapIcons.register();
        Pl3xMap.api().getEventRegistry().register(this);
        // Welten, die Pl3xMap schon geladen hat; später geladene kommen über WorldLoadedEvent
        Pl3xMap.api().getWorldRegistry().values().forEach(this::addLayers);

        // Warps und Spawns ändern sich selten, Mobs ständig
        Bukkit.getScheduler().runTaskTimer(this, this::updatePlaces, 40L, 200L);
        long mobTicks = Math.max(1, getConfig().getInt("mobs.update-seconds")) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::updateMobs, 40L, mobTicks);
        getLogger().info("Pl3xMap-Ebenen für Nucleus aktiv.");

        // Minecraft-Texturen für Mob- und Warp-Symbole im Hintergrund bereitstellen
        textures = new Textures(getDataFolder().toPath().resolve("textures"), getLogger());
        String version = Bukkit.getMinecraftVersion();
        String clientJar = getConfig().getString("textures.client-jar", "");
        boolean download = getConfig().getBoolean("textures.download-from-mojang");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (textures.prepare(version, clientJar == null ? "" : clientJar, download)) {
                registerSpawnEggs();
                Bukkit.getScheduler().runTask(this, () -> {
                    updatePlaces();
                    updateMobs();
                });
            }
        });
    }

    @Override
    public void onDisable() {
        layers.forEach((worldName, byKey) -> {
            World world = Pl3xMap.api().getWorldRegistry().get(worldName);
            if (world != null) {
                byKey.keySet().forEach(world.getLayerRegistry()::unregister);
            }
        });
        layers.clear();
    }

    // --- Pl3xMap-Ereignisse (können aus Pl3xMap-Threads kommen) ---

    @EventHandler
    public void onWorldLoaded(WorldLoadedEvent event) {
        addLayers(event.getWorld());
    }

    @EventHandler
    public void onWorldUnloaded(WorldUnloadedEvent event) {
        layers.remove(event.getWorld().getName());
    }

    private void addLayers(World world) {
        if (!world.isEnabled()) {
            return;
        }
        Map<String, MapLayer> byKey = new ConcurrentHashMap<>();
        addLayer(world, byKey, WARPS, "warps", "Warps", 10, 30);
        addLayer(world, byKey, SPAWNS, "spawns", english ? "Spawn points" : "Spawnpunkte", 10, 31);
        int mobSeconds = Math.max(1, getConfig().getInt("mobs.update-seconds"));
        addLayer(world, byKey, MOBS, "mobs", "Mobs", mobSeconds, 20);
        layers.put(world.getName(), byKey);
    }

    private void addLayer(World world, Map<String, MapLayer> byKey, String key, String section, String defaultLabel,
                          int updateSeconds, int priority) {
        ConfigurationSection c = getConfig().getConfigurationSection(section);
        if (c == null || !c.getBoolean("enabled")) {
            return;
        }
        String label = c.getString("label", "");
        String shown = label == null || label.isBlank() ? defaultLabel : label;
        MapLayer layer = new MapLayer(key, world, () -> shown, updateSeconds, c.getBoolean("default-hidden"), priority);
        world.getLayerRegistry().register(layer);
        byKey.put(key, layer);
    }

    /** Pl3xMap-Ebenen zu einer Bukkit-Welt (Pl3xMap benennt Welten nach dem Bukkit-Namen). */
    private @Nullable Map<String, MapLayer> layersFor(org.bukkit.World world) {
        Map<String, MapLayer> byKey = layers.get(world.getName());
        return byKey != null ? byKey : layers.get(world.getKey().toString());
    }

    // --- Symbole aus Texturen ---

    private static final int MOB_SIZE = 16;
    private static final int WARP_SIZE = 24;

    /** Alle Spawn-Eier als Symbole registrieren (Hintergrund-Thread). */
    private void registerSpawnEggs() {
        for (org.bukkit.entity.EntityType type : org.bukkit.entity.EntityType.values()) {
            if (type == org.bukkit.entity.EntityType.UNKNOWN || !type.isAlive()) {
                continue;
            }
            String mob = type.getKey().getKey();
            var img = textures.spawnEgg(mob);
            if (img != null) {
                MapIcons.registerTexture("nucleus_egg_" + mob, img, MOB_SIZE * 2);
                textureIcons.add("nucleus_egg_" + mob);
            }
        }
    }

    /**
     * Symbol für ein Item (Warp-Symbol aus dem GUI). Fehlt es noch, wird es im Hintergrund erzeugt und bis dahin
     * {@code null} geliefert (dann gilt das einfache Symbol).
     */
    private @Nullable String itemIcon(String itemKey) {
        String key = "nucleus_item_" + itemKey.replace(':', '_');
        if (textureIcons.contains(key)) {
            return key;
        }
        if (textures != null && textures.ready() && pendingIcons.add(key)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                var img = textures.item(itemKey);
                if (img != null) {
                    MapIcons.registerTexture(key, img, WARP_SIZE * 2);
                    textureIcons.add(key);
                    Bukkit.getScheduler().runTask(this, this::updatePlaces);
                }
            });
        }
        return null;
    }

    // --- Warps und Spawns ---

    private void updatePlaces() {
        TeleportModule teleport = nucleus.modules().get("teleport", TeleportModule.class);
        Set<String> hidden = new HashSet<>();
        getConfig().getStringList("warps.hidden").forEach(n -> hidden.add(n.toLowerCase(Locale.ROOT)));

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            Map<String, MapLayer> byKey = layersFor(world);
            if (byKey == null) {
                continue;
            }
            MapLayer warpLayer = byKey.get(WARPS);
            if (warpLayer != null) {
                List<Marker<?>> markers = new ArrayList<>();
                if (teleport != null) {
                    teleport.warps().all().forEach((name, loc) -> {
                        if (loc.world().equals(world.getName()) && !hidden.contains(name)) {
                            String item = teleport.warps().icon(name);
                            String icon = itemIcon(item != null ? item : "minecraft:ender_pearl");
                            markers.add(icon != null
                                    ? place("warp_" + name, loc, icon, WARP_SIZE, name, "/warp " + name)
                                    : place("warp_" + name, loc, MapIcons.WARP, 20, name, "/warp " + name));
                        }
                    });
                }
                warpLayer.update(markers);
            }
            MapLayer spawnLayer = byKey.get(SPAWNS);
            if (spawnLayer != null) {
                List<Marker<?>> markers = new ArrayList<>();
                if (teleport != null) {
                    // Liegen globaler Spawn und Welt-Spawn an derselben Stelle, nur einen Marker zeigen.
                    // Liegt der Nucleus-Spawn am Vanilla-Weltspawn, reicht das Spawn-Symbol von Pl3xMap.
                    Set<String> seen = new HashSet<>();
                    double hideRadius = getConfig().getDouble("spawns.hide-near-world-spawn");
                    var worldSpawn = world.getSpawnLocation();
                    teleport.spawns().all().forEach((key, loc) -> {
                        String pos = loc.world() + ":" + (int) loc.x() + ":" + (int) loc.z();
                        double dx = loc.x() - worldSpawn.getX();
                        double dz = loc.z() - worldSpawn.getZ();
                        boolean atWorldSpawn = hideRadius > 0 && dx * dx + dz * dz <= hideRadius * hideRadius;
                        if (loc.world().equals(world.getName()) && !atWorldSpawn && seen.add(pos)) {
                            String title = "global".equals(key) ? "Spawn" : "Spawn (" + key + ")";
                            markers.add(place("spawn_" + key, loc, MapIcons.SPAWN, 20, title, "/spawn"));
                        }
                    });
                }
                spawnLayer.update(markers);
            }
        }
    }

    private static Marker<?> place(String key, NamedLocation loc, String icon, int size, String title, String command) {
        String popup = "<b>" + html(title) + "</b><br>" + html(command) + "<br>"
                + (int) Math.floor(loc.x()) + ", " + (int) Math.floor(loc.y()) + ", " + (int) Math.floor(loc.z());
        return Marker.icon(key, Point.of(loc.x(), loc.z()), icon, size)
                .setOptions(Options.builder().tooltipContent(html(title)).popupContent(popup).build());
    }

    // --- Mobs ---

    private void updateMobs() {
        ConfigurationSection c = getConfig().getConfigurationSection("mobs");
        if (c == null || !c.getBoolean("enabled")) {
            return;
        }
        int max = Math.max(0, c.getInt("max-per-world"));
        boolean hostile = c.getBoolean("hostile");
        boolean passive = c.getBoolean("passive");
        boolean other = c.getBoolean("other");

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            Map<String, MapLayer> byKey = layersFor(world);
            MapLayer layer = byKey == null ? null : byKey.get(MOBS);
            if (layer == null) {
                continue;
            }
            List<Marker<?>> markers = new ArrayList<>();
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (markers.size() >= max) {
                    break;
                }
                String icon;
                if (mob instanceof Enemy) {
                    if (!hostile) continue;
                    icon = MapIcons.MOB_HOSTILE;
                } else if (mob instanceof Animals || mob instanceof WaterMob || mob instanceof Ambient) {
                    if (!passive) continue;
                    icon = MapIcons.MOB_PASSIVE;
                } else {
                    if (!other) continue;
                    icon = MapIcons.MOB_OTHER;
                }
                var loc = mob.getLocation();
                String egg = "nucleus_egg_" + mob.getType().getKey().getKey();
                boolean hasEgg = textureIcons.contains(egg);
                markers.add(Marker.icon("mob_" + mob.getUniqueId(), Point.of(loc.getX(), loc.getZ()),
                                hasEgg ? egg : icon, hasEgg ? MOB_SIZE : 10.0)
                        .setOptions(Options.builder().tooltipContent(html(mob.getName())).build()));
            }
            layer.update(markers);
        }
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
