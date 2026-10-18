# Nucleus

Schlanker, modularer Ersatz für die wichtigsten EssentialsX-Funktionen auf **Paper 26.3** (Java 25).
Komplett neu geschrieben (Clean-Room, kein Code und keine Texte aus EssentialsX) und vollständig mit KI erstellt.

- **Modular:** Jedes Modul lässt sich in der `config.yml` abschalten. Ein abgeschaltetes Modul lädt keinen Code,
  registriert keine Befehle und keine Listener. Vanilla-Befehle bleiben unter `/minecraft:…` erreichbar.
- **Aktuell:** nur Paper-26.3-APIs (Brigadier-Befehle, Datenkomponenten, Dialoge, MenuType), keine veralteten APIs.
- **Ohne Abhängigkeiten:** kein Vault, kein PlaceholderAPI, kein LuckPerms nötig.
  VaultUnlocked wird optional unterstützt.
- **Zweisprachig:** Deutsch und Englisch (`language: de|en`), alle Texte in `lang/*.yml` anpassbar.
- **Nicht blockierend:** Spielerdaten und Dateien werden asynchron gelesen und geschrieben.

## Module

| Modul | Inhalt |
|---|---|
| `teleport` | Homes (mit GUI), Warps (mit GUI), Spawn, TPA mit Klick-Buttons, `/back`, Admin-Teleports, Wartezeit mit Countdown, Cooldowns, sichere Zielsuche |
| `chat` | Chat-Format (MiniMessage), Tags im Chat und in der Tab-Liste (`/tag`), `/msg`, `/r`, `/ignore`, SocialSpy, Nicknames, `/me`, `/broadcast`, Mail, Join-/Quit-Nachrichten (standardmäßig aus) |
| `info` | `/list`, `/seen`, `/whois`, `/near`, `/ping`, `/playtime`, AFK-System, MOTD und Regeln (im Spiel bearbeitbar) |
| `economy` | Konten in Cent (keine Rundungsfehler), `/pay`, `/baltop`, `/eco`, öffentliche `EconomyService`-API, VaultUnlocked-Anbindung |
| `moderation` | Kick, Ban, Tempban, IP-Ban, Mute, Freeze, Jail, Vanish, Invsee, Enderchest, Sudo, CommandSpy, `moderation.log` |
| `gameplay` | Gamemode, Fly, God, Heal, Feed, Speed, Zeit/Wetter, Items, Enchant, Lore, Köpfe, Powertools, tragbare Werkbänke |
| `lock` | Truhen und andere Behälter mit `/lock` sichern, optional mit Passwort (Dialog, nur als Hash gespeichert) |

## Installation

1. `Nucleus.jar` in den `plugins/`-Ordner legen und den Server starten.
2. Module in `plugins/Nucleus/config.yml` an- oder abschalten, Einstellungen in `plugins/Nucleus/modules/<modul>.yml`.
3. Änderungen mit `/nucleus reload` übernehmen. Neu ein- oder ausgeschaltete Module bekommen bzw. verlieren
   ihre Befehle nach einem Neustart oder `/minecraft:reload`.

### Von EssentialsX umsteigen

`/nucleus import essentials` übernimmt aus `plugins/Essentials` Homes, Warps, Spawn, Jails, Kontostände,
Nicknames, `/back`-Positionen, Ignorierlisten und Mutes. Es wird nur ergänzt, nie überschrieben; der Import
kann gefahrlos wiederholt werden.

Stammen die Daten von einem Server mit `online-mode=true`, Nucleus läuft aber mit `online-mode=false`, haben die
Spieler dort andere UUIDs. Dann `/nucleus import essentials offline` benutzen: die UUIDs werden über den
Spielernamen umgerechnet.

### Vom TagPlugin (TagMC) umsteigen

Bei aktivem `chat`-Modul übernimmt `/nucleus import tagplugin` aus `plugins/TagPlugin` alle Tags, vergebene Tags
und die Formate für Chat und Tab-Liste. Danach das TagPlugin entfernen, damit `/tag`, Chat und Tab-Liste nur
noch von Nucleus kommen.

### VaultUnlocked

Ist [VaultUnlocked](https://modrinth.com/project/vaultunlocked) installiert, meldet sich Nucleus automatisch als
Economy an (moderne und klassische Vault-API). Abschaltbar mit `vault.enabled: false` in `modules/economy.yml`.

## Dateien

```
plugins/Nucleus/
├── config.yml          Module an/aus, Sprache, Farben, Speichern
├── modules/*.yml       Einstellungen pro Modul
├── lang/de.yml, en.yml Alle Texte (MiniMessage)
├── playerdata/*.yml    Spielerdaten pro UUID
├── economy.yml         Kontostände
├── tags.yml            Tags (chat-Modul)
├── warps.yml, spawns.yml, jails.yml
└── moderation.log      Protokoll aller Moderationsaktionen
```

## Für Entwickler

```bash
./gradlew build    # bauen und Tests ausführen
./gradlew deploy   # Jar in den lokalen Testserver kopieren
```

Andere Plugins erreichen die Economy über den ServicesManager:

```java
EconomyService eco = Bukkit.getServicesManager().load(EconomyService.class);
```

Beträge sind immer `long` in der kleinsten Einheit (bei 2 Nachkommastellen: Cent).
