package de.nucleus.addon.pl3xmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Item- und Blocktexturen aus dem Minecraft-Client. Die Bilder gehören Mojang und liegen deshalb nicht im
 * Plugin: sie werden einmal pro Minecraft-Version aus einer Client-Jar (vorhandene Datei oder Download von
 * Mojang) nach plugins/Nucleus-Pl3xMap/textures/ entpackt. Alle Methoden laufen außerhalb des Hauptthreads.
 */
final class Textures {

    private static final String PREFIX = "assets/minecraft/textures/";
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** Graue Texturen, die im Spiel je nach Biom eingefärbt werden. */
    private static final Set<String> GRASS_TINT = Set.of("grass_block_top", "short_grass", "tall_grass_top", "fern",
            "large_fern_top", "sugar_cane");
    private static final Set<String> UNTINTED_LEAVES = Set.of("cherry_leaves", "azalea_leaves",
            "flowering_azalea_leaves", "pale_oak_leaves");

    private final Path dir;
    private final Logger log;
    private volatile boolean ready;

    Textures(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    boolean ready() {
        return ready;
    }

    /**
     * Sorgt dafür, dass die Texturen für {@code version} entpackt vorliegen.
     * @return true, wenn Texturen da sind
     */
    boolean prepare(String version, String clientJar, boolean download) {
        Path stamp = dir.resolve("version.txt");
        try {
            if (Files.isRegularFile(stamp) && Files.readString(stamp).strip().equals(version)) {
                ready = true;
                return true;
            }
            if (!clientJar.isBlank()) {
                Path jar = Path.of(clientJar);
                if (!Files.isRegularFile(jar)) {
                    log.warning("textures.client-jar nicht gefunden: " + jar);
                    return false;
                }
                extract(jar, version);
            } else if (download) {
                Path jar = downloadClient(version);
                try {
                    extract(jar, version);
                } finally {
                    Files.deleteIfExists(jar);
                }
            } else {
                log.info("Ohne Minecraft-Texturen gibt es nur einfache Symbole. Für Mob- und Warp-Symbole in "
                        + "config.yml textures.download-from-mojang: true setzen (oder textures.client-jar).");
                return false;
            }
            ready = true;
            return true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warning("Texturen konnten nicht geladen werden: " + e);
            return false;
        }
    }

    private void extract(Path jar, String version) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".png")
                        || !(name.startsWith(PREFIX + "item/") || name.startsWith(PREFIX + "block/"))) {
                    continue;
                }
                Path target = dir.resolve(name.substring(PREFIX.length())).normalize();
                if (!target.startsWith(dir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        if (count == 0) {
            throw new IOException("keine Texturen in " + jar + " (ist das eine Client-Jar?)");
        }
        Files.writeString(dir.resolve("version.txt"), version);
        log.info(count + " Minecraft-Texturen für " + version + " bereitgestellt.");
    }

    /** Lädt die offizielle Client-Jar dieser Version von Mojang und prüft die SHA-1-Prüfsumme. */
    private Path downloadClient(String version) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        JsonObject manifest = json(http, MANIFEST);
        String versionUrl = null;
        for (var v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (version.equals(o.get("id").getAsString())) {
                versionUrl = o.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Version " + version + " nicht im Mojang-Manifest");
        }
        JsonObject client = json(http, versionUrl).getAsJsonObject("downloads").getAsJsonObject("client");
        String url = client.get("url").getAsString();
        String sha1 = client.get("sha1").getAsString();
        log.info("Lade Minecraft-Client " + version + " von Mojang für die Kartensymbole ...");
        Files.createDirectories(dir);
        Path jar = dir.resolve("client-" + version + ".jar.part");
        HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(jar));
        if (res.statusCode() != 200) {
            Files.deleteIfExists(jar);
            throw new IOException("HTTP " + res.statusCode() + " beim Laden der Client-Jar");
        }
        String actual = sha1(jar);
        if (!actual.equalsIgnoreCase(sha1)) {
            Files.deleteIfExists(jar);
            throw new IOException("Prüfsumme der Client-Jar stimmt nicht");
        }
        return jar;
    }

    private static JsonObject json(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " für " + url);
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Spawn-Ei eines Mobs, z. B. "zombie" → item/zombie_spawn_egg.png. */
    @Nullable BufferedImage spawnEgg(String entityKey) {
        return read("item/" + entityKey + "_spawn_egg.png");
    }

    /**
     * Symbol für ein Item, z. B. "minecraft:grass_block": erst die Item-Textur, sonst eine passende Blockseite.
     */
    @Nullable BufferedImage item(String itemKey) {
        String name = itemKey.contains(":") ? itemKey.substring(itemKey.indexOf(':') + 1) : itemKey;
        for (String candidate : List.of("item/" + name, "block/" + name, "block/" + name + "_front",
                "block/" + name + "_top", "block/" + name + "_side")) {
            BufferedImage img = read(candidate + ".png");
            if (img != null) {
                return tint(candidate.substring(candidate.indexOf('/') + 1), img);
            }
        }
        return null;
    }

    private @Nullable BufferedImage read(String relative) {
        if (!ready) {
            return null;
        }
        Path file = dir.resolve(relative).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) {
                return null;
            }
            // Animierte Texturen sind Streifen aus mehreren Bildern: nur das erste nehmen
            if (img.getHeight() > img.getWidth()) {
                img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
            }
            return img;
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage tint(String name, BufferedImage img) {
        Color color;
        if (GRASS_TINT.contains(name)) {
            color = new Color(0x91, 0xBD, 0x59);
        } else if ((name.endsWith("_leaves") && !UNTINTED_LEAVES.contains(name)) || name.equals("vine")
                || name.equals("lily_pad")) {
            color = new Color(0x77, 0xAB, 0x2F);
        } else {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24;
                int r = ((argb >> 16) & 0xFF) * color.getRed() / 255;
                int g = ((argb >> 8) & 0xFF) * color.getGreen() / 255;
                int b = (argb & 0xFF) * color.getBlue() / 255;
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }
}
