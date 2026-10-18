# Nucleus-Pl3xMap

Add-on für [Pl3xMap](https://modrinth.com/plugin/pl3xmap): zeigt auf der Webkarte drei zusätzliche Ebenen.

| Ebene | Inhalt |
|---|---|
| Warps | Alle Nucleus-Warps (goldener Punkt), Klick zeigt Name, `/warp <name>` und Koordinaten |
| Spawnpunkte | Spawns aus `/setspawn` (grünes Haus) |
| Mobs | Lebende Mobs: rot = feindlich, grün = friedlich, gelb = sonstige (z. B. Dorfbewohner) |

Jede Ebene lässt sich in der Karte ein- und ausblenden und in `plugins/Nucleus-Pl3xMap/config.yml` abschalten
(dort auch: Warps verstecken, Aktualisierung der Mobs, Höchstzahl pro Welt).

## Installation

`Nucleus.jar`, `Pl3xMap` und `Nucleus-Pl3xMap.jar` in `plugins/` legen und den Server starten.

## Bauen

Pl3xMap veröffentlicht für Minecraft 26.x keine Maven-API. Zum Bauen die Pl3xMap-Jar als
`addons/pl3xmap/libs/Pl3xMap.jar` ablegen; dann baut `./gradlew build` das Add-on mit
(`addons/pl3xmap/build/libs/Nucleus-Pl3xMap.jar`). Fehlt die Datei, wird nur Nucleus gebaut.
