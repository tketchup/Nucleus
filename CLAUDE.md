# Nucleus

Schlanker, modularer EssentialsX-Ersatz (Clean-Room-Neubau, kein Code und keine Texte aus EssentialsX)
für Paper 26.3 (Java 25, Gradle Kotlin DSL). Permission-Präfix `nucleus.`, Hauptbefehl `/nucleus`.

Die Shell des Nutzers ist fish: keine bash-Heredocs oder Brace-Expansion in Befehlen.

## Ablauf nach jeder Änderung

Testserver: `~/Documents/code/testserver` (Details in `~/Documents/code/testserver/CLAUDE.md`).

1. **Server stoppen**, dann etwa 8 Sekunden warten, bis die tmux-Sitzung `mc` beendet ist:
   ```
   tmux send-keys -t mc 'stop' Enter
   ```
2. **Bauen und deployen** (kopiert `build/libs/Nucleus.jar` nach `testserver/plugins/`):
   ```
   ./gradlew deploy
   ```
3. **Server starten** (im Verzeichnis `~/Documents/code/testserver`):
   ```
   tmux new -d -s mc 'java -Xms2G -Xmx2G -jar paper.jar --nogui'
   ```
   Bereit, sobald `Done (` in `logs/latest.log` steht.
4. **Log prüfen**: `~/Documents/code/testserver/logs/latest.log` auf `ERROR`, `WARN` und Exceptions rund um
   `Nucleus` durchsehen und sicherstellen, dass das Plugin aktiviert wurde.
5. **Funktionen per RCON testen**, z. B.:
   ```
   mcrcon -H localhost -P 25575 -p test 'nucleus modules'
   ```
6. Unit-Tests: `./gradlew test`

## Wichtig

- Der Testserver wird von mehreren Plugins gemeinsam genutzt: **niemals andere Jars oder Ordner in
  `plugins/` löschen oder ändern.** Nur `Nucleus.jar` und `plugins/Nucleus/` gehören zu diesem Projekt.
- Niemals `/reload` benutzen, immer stoppen und neu starten.
- Befehle werden nur per Brigadier (`LifecycleEvents.COMMANDS`) registriert, nicht in `plugin.yml`.
  Der Handler meldet nur Befehle aktiver Module an. Modul per `/nucleus reload` an/aus → Befehle werden
  sofort gesperrt bzw. erscheinen nach Neustart oder `/minecraft:reload` (Datapack-Reload, nicht Bukkit-/reload).
- Neue Module in `ModuleManager.KNOWN` eintragen (nur Klassenname, wird erst bei Aktivierung geladen).
- RCON zeigt nur sofortige Antworten. Befehle mit asynchronem Laden (z. B. `homes <offline-spieler>`)
  antworten später: dafür `tmux send-keys -t mc '<befehl>' Enter` und das Log lesen.
- Das Bash-Tool läuft in einer POSIX-Shell (nicht fish): Schleifen in sh-Syntax schreiben.
- Einstellungen: `config.yml` nur für Module an/aus, Sprache, Farben, Speichern. Jedes Modul liest
  `modules/<modul>.yml` über `plugin.config("<modul>")` (Pfade ohne Modul-Präfix); im Spiel geändert →
  `plugin.moduleConfigs().save("<modul>")`. `ModuleConfigs.MIGRATION` übernimmt alte config.yml-Abschnitte.
- VaultUnlocked (Plugin-Name "Vault", softdepend) ist auf dem Testserver installiert. Nur `VaultHook`,
  `ModernVaultEconomy`, `LegacyVaultEconomy` kennen Vault-Klassen; Prüfen mit `vault-info` per RCON.
- Lock-Modul speichert Sperren im PersistentDataContainer des Blocks; Passwörter nur per Dialog und als
  PBKDF2-Hash (`util/Passwords`), Hashen im Hintergrund-Thread.
- Messages und config.yml: Getter ohne Fallback benutzen (`getInt(path)`, `Configs.string(c, path)`),
  sonst greifen die Defaults aus dem Jar nicht, wenn die Server-Datei von einer älteren Version stammt.
- Texte liegen in `src/main/resources/lang/de.yml` und `lang/en.yml` (Auswahl: `language` in config.yml).
  Neue Nachrichten immer in BEIDE Dateien eintragen; `LanguageFilesTest` prüft gleiche Schlüssel und dass jeder
  im Code benutzte Schlüssel existiert. YAML-Schlüssel nie `yes`/`no`/`on`/`off` nennen (werden zu true/false).
- Keine festen Farben in den Sprachdateien, sondern `<hl>` (Hervorhebung) und `<err>` (Fehler); die Grundfarbe
  setzt `Messages` aus `messages.color/highlight/error`. Bei grundlegend geänderten Texten `Messages.VERSION` und
  `version:` in beiden Sprachdateien erhöhen (alte Datei wird als .bak gesichert).
- Dauer/Datum/Zahlen für Spieler über `messages().duration()/date()` und `Formats` (sprachabhängig);
  Befehlsbeschreibungen über `commands.<befehl>`, Benutzungswörter über `usage-words` in en.yml.
- GUI: `de.nucleus.gui` (Menu + MenuListener schützen jedes Menü, Icons für Items/Schlüssel, Dialogs für
  Texteingabe/Bestätigung über die Paper Dialog-API; Callbacks werden per `plugin.sync` auf den Hauptthread geholt).
- Änderungen im Spiel an der config.yml: `getConfig().set(...)`, dann `plugin.saveConfigAsync()` und die
  Modul-Einstellungen neu laden (Beispiel: `/motd add`).
- moderation.log ist bewusst sprachneutral (englische Schlüssel, Dauer als `1d2h`).
- Welten ohne Welt-Uhr (Nether, End) haben in 26.x keine Tageszeit: vor `setTime` `isFixedTime()` prüfen.
- Welt-Argumente über `CommandKit.worldArg`/`world` (Weltname), nicht `ArgumentTypes.world()` (Dimensions-Key).
- Das chat-Modul ist auf dem Testserver aus (TagMC). Zum Testen in `plugins/Nucleus/config.yml`
  vorübergehend `chat: true` setzen und danach wiederherstellen.
