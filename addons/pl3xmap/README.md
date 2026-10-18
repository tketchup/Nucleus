# Nucleus-Pl3xMap

Add-on für [Pl3xMap](https://modrinth.com/plugin/pl3xmap): zeigt auf der Webkarte drei zusätzliche Ebenen.

| Ebene | Inhalt |
|---|---|
| Warps | Alle Nucleus-Warps mit dem Symbol aus dem Warps-GUI, Klick zeigt Name, `/warp <name>` und Koordinaten |
| Spawnpunkte | Spawns aus `/setspawn` (grünes Haus); liegt er am Weltspawn, reicht das Spawn-Symbol von Pl3xMap |
| Mobs | Lebende Mobs als Spawn-Ei |

### Symbole

Die Symbole für Mobs und Warps stammen aus den Minecraft-Texturen. Die gehören Mojang und sind deshalb nicht im
Add-on enthalten: In `config.yml` entweder `textures.download-from-mojang: true` setzen (lädt die Client-Jar
einmal pro Minecraft-Version von Mojang, prüft sie und löscht sie nach dem Entpacken) oder unter
`textures.client-jar` den Pfad zu einer vorhandenen Client-Jar angeben. Ohne Texturen gibt es farbige Punkte
(rot = feindlich, grün = friedlich, gelb = sonstige) und einen goldenen Punkt für Warps.

Jede Ebene lässt sich in der Karte ein- und ausblenden und in `plugins/Nucleus-Pl3xMap/config.yml` abschalten
(dort auch: Warps verstecken, Aktualisierung der Mobs, Höchstzahl pro Welt).

## Installation

`Nucleus.jar`, `Pl3xMap` und `Nucleus-Pl3xMap.jar` in `plugins/` legen und den Server starten.

## Bauen

Pl3xMap veröffentlicht für Minecraft 26.x keine Maven-API. Zum Bauen die Pl3xMap-Jar als
`addons/pl3xmap/libs/Pl3xMap.jar` ablegen; dann baut `./gradlew build` das Add-on mit
(`addons/pl3xmap/build/libs/Nucleus-Pl3xMap.jar`). Fehlt die Datei, wird nur Nucleus gebaut.
