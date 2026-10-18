package de.nucleus.core;

import de.nucleus.NucleusPlugin;
import de.nucleus.util.ConfigUpdater;
import de.nucleus.util.Formats;
import de.nucleus.util.TimeParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texte aus {@code plugins/Nucleus/lang/<sprache>.yml} (MiniMessage). Die Sprache steht in der
 * config.yml unter {@code language} (de oder en). Beide Dateien werden angelegt und lassen sich
 * bearbeiten; fehlende Schlüssel fallen auf die mitgelieferte Datei derselben Sprache zurück.
 *
 * <p>Platzhalter werden als Paare übergeben: {@code send(sender, "home.set", "name", "basis")}. Texte
 * werden "unparsed" eingesetzt (Spielereingaben können also keine Formatierung einschleusen),
 * Components direkt. {@code <prefix>} steht in jeder Nachricht zur Verfügung.
 *
 * <p>Farben (config.yml, Abschnitt {@code messages}): Jede Nachricht bekommt die Grundfarbe,
 * {@code <hl>…</hl>} hebt Namen und Werte hervor, {@code <err>} färbt Fehler.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Erhöhen, wenn sich die mitgelieferten Texte grundlegend ändern: alte Dateien werden dann ersetzt. */
    private static final int VERSION = 4;
    private static final List<String> LANGUAGES = List.of("de", "en");
    /** Wörter in Benutzungshinweisen, z. B. "spieler" in "/tpa <spieler>". */
    private static final Pattern USAGE_WORD = Pattern.compile("[A-Za-zÄÖÜäöüß]+");

    private final NucleusPlugin plugin;
    private String language = "de";
    private YamlConfiguration yaml = new YamlConfiguration();
    private Component prefix = Component.empty();
    private TextColor baseColor = TextColor.color(0x5DADEC);
    private TextColor highlightColor = NamedTextColor.WHITE;
    private TextColor errorColor = TextColor.color(0xFF6B6B);
    private DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private TimeParser.Units timeUnits = TimeParser.Units.GERMAN;

    public Messages(NucleusPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lädt die Sprachdatei (klein, einmal beim Start und bei /nucleus reload). */
    public void load() {
        migrateOldFile();
        for (String lang : LANGUAGES) {
            if (!new File(plugin.getDataFolder(), "lang/" + lang + ".yml").exists()) {
                plugin.saveResource("lang/" + lang + ".yml", false);
            }
        }
        String configured = String.valueOf(plugin.getConfig().getString("language")).toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(configured)) {
            plugin.getLogger().warning("Unbekannte Sprache '" + configured + "' in config.yml, nutze 'de'.");
            configured = "de";
        }
        language = configured;
        yaml = loadLanguage(language);

        String prefixText = yaml.getString("prefix");
        prefix = MM.deserialize(prefixText != null ? prefixText : "");
        baseColor = color("messages.color", baseColor);
        highlightColor = color("messages.highlight", highlightColor);
        errorColor = color("messages.error", errorColor);
        dateFormat = DateTimeFormatter.ofPattern(raw("format.date"), locale()).withZone(ZoneId.systemDefault());
        timeUnits = new TimeParser.Units(raw("time.day"), raw("time.days"), raw("time.hour"), raw("time.hours"),
                raw("time.minute"), raw("time.minutes"), raw("time.second"), raw("time.seconds"));
        Formats.locale(locale());
    }

    /** Lädt lang/&lt;sprache&gt;.yml, ersetzt veraltete Versionen und ergänzt neue Schlüssel. */
    private YamlConfiguration loadLanguage(String lang) {
        String resource = "lang/" + lang + ".yml";
        File file = new File(plugin.getDataFolder(), resource);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        // getInt mit Fallback liest absichtlich nur die Datei selbst (ohne Defaults)
        int fileVersion = loaded.getInt("version", 1);
        if (fileVersion < VERSION) {
            File backup = new File(file.getParentFile(), lang + ".yml.v" + fileVersion + ".bak");
            if (file.renameTo(backup)) {
                plugin.saveResource(resource, true);
                loaded = YamlConfiguration.loadConfiguration(file);
                plugin.getLogger().info(resource + " auf Version " + VERSION + " aktualisiert, alte Datei: " + backup.getName());
            }
        }
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
                // Neue Nachrichten in die Datei des Servers übernehmen, damit man sie dort findet und ändern kann
                int added = ConfigUpdater.addMissing(loaded, defaults);
                if (added > 0) {
                    plugin.io().write(file.toPath(), loaded.saveToString());
                    plugin.getLogger().info(resource + " um " + added + " neue Einträge ergänzt.");
                }
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Standardtexte konnten nicht gelesen werden: " + e.getMessage());
        }
        return loaded;
    }

    /** Frühere Versionen hatten eine einzelne messages.yml; die wird beiseitegelegt. */
    private void migrateOldFile() {
        File old = new File(plugin.getDataFolder(), "messages.yml");
        if (old.exists()) {
            File backup = new File(plugin.getDataFolder(), "messages.yml.old");
            if (old.renameTo(backup)) {
                plugin.getLogger().info("Texte liegen jetzt in lang/de.yml und lang/en.yml, alte messages.yml → messages.yml.old");
            }
        }
    }

    /** Hex ("#5DADEC") oder Name ("aqua") aus der config.yml; ungültig = bisheriger Wert. */
    private TextColor color(String path, TextColor fallback) {
        String value = plugin.getConfig().getString(path);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        TextColor c = value.startsWith("#") ? TextColor.fromHexString(value)
                : NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        if (c == null) {
            plugin.getLogger().warning("Ungültige Farbe in config.yml bei " + path + ": '" + value + "'");
            return fallback;
        }
        return c;
    }

    public String language() {
        return language;
    }

    public Locale locale() {
        return language.equals("en") ? Locale.UK : Locale.GERMANY;
    }

    public String raw(String key) {
        // Wichtig: getString(key) ohne Fallback-Parameter, nur diese Variante fragt die Defaults
        // (mitgelieferte Sprachdatei) ab. getString(key, def) würde sie überspringen.
        String text = yaml.getString(key);
        return text != null ? text : "<red>Missing message: " + key;
    }

    public List<String> rawList(String key) {
        return yaml.getStringList(key);
    }

    /** Nachricht in der Grundfarbe (explizite Farben im Text haben Vorrang). */
    public Component get(String key, Object... placeholders) {
        return parse(raw(key), placeholders).colorIfAbsent(baseColor);
    }

    /**
     * Mehrzeiliger Text als einzelne Zeilen, getrennt an {@code <newline>}. Für Item-Beschreibungen:
     * dort darf eine Zeile keinen Umbruch enthalten (Minecraft zeigt ihn sonst als "LF"-Symbol).
     */
    public List<Component> lines(String key, Object... placeholders) {
        List<Component> out = new ArrayList<>();
        for (String part : raw(key).split("<newline>|<br>|\\n")) {
            out.add(parse(part, placeholders).colorIfAbsent(baseColor));
        }
        return out;
    }

    /** MiniMessage-Text mit denselben Platzhalter-Regeln parsen (z. B. für Formate aus der config.yml). */
    public Component parse(String miniMessage, Object... placeholders) {
        return MM.deserialize(miniMessage, resolver(placeholders));
    }

    /** Sendet die Nachricht; ein leerer Text schaltet sie ab. */
    public void send(CommandSender to, String key, Object... placeholders) {
        String text = raw(key);
        if (!text.isEmpty()) {
            to.sendMessage(parse(text, placeholders).colorIfAbsent(baseColor));
        }
    }

    // --- Sprachabhängige Formatierung ---

    /** Dauer in der eingestellten Sprache, z. B. "2 Stunden, 30 Minuten" oder "2 hours, 30 minutes". */
    public String duration(Duration duration) {
        return TimeParser.format(duration, timeUnits);
    }

    public String date(long epochMillis) {
        return dateFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Übersetzt die Wörter eines Benutzungshinweises über den Abschnitt usage-words. */
    public String usage(String usage) {
        ConfigurationSection words = yaml.getConfigurationSection("usage-words");
        if (words == null) {
            return usage;
        }
        Matcher m = USAGE_WORD.matcher(usage);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String translated = words.getString(m.group().toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(translated != null ? translated : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Beschreibung für /help aus commands.&lt;befehl&gt;, sonst der Text aus dem Code. */
    public String commandDescription(String command, String fallback) {
        String text = yaml.getString("commands." + command);
        return text != null ? text : fallback;
    }

    private TagResolver resolver(Object... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Platzhalter müssen als Paare übergeben werden");
        }
        List<TagResolver> list = new ArrayList<>(placeholders.length / 2 + 3);
        list.add(Placeholder.component("prefix", prefix));
        list.add(Placeholder.styling("hl", highlightColor));
        list.add(Placeholder.styling("err", errorColor));
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            if (value instanceof ComponentLike c) {
                list.add(Placeholder.component(name, c));
            } else if (value instanceof TagResolver r) {
                list.add(r);
            } else {
                list.add(Placeholder.unparsed(name, String.valueOf(value)));
            }
        }
        return TagResolver.resolver(list);
    }
}
