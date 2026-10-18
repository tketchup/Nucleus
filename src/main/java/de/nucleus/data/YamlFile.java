package de.nucleus.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Eine YAML-Datei (z. B. warps.yml), die asynchron über den IO-Thread gelesen und geschrieben wird. */
public final class YamlFile {

    private final AsyncIO io;
    private final Path path;

    public YamlFile(AsyncIO io, Path path) {
        this.io = io;
        this.path = path;
    }

    /** IO-Thread liest; fehlende Datei ergibt eine leere Konfiguration. */
    public CompletableFuture<YamlConfiguration> load() {
        return io.supply(() -> {
            YamlConfiguration yaml = new YamlConfiguration();
            if (Files.exists(path)) {
                yaml.loadFromString(Files.readString(path));
            }
            return yaml;
        });
    }

    /** Auf dem Hauptthread aufrufen: der Text wird hier erzeugt, geschrieben wird im IO-Thread. */
    public void save(YamlConfiguration yaml) {
        io.write(path, yaml.saveToString());
    }
}
