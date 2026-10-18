package de.nucleus.module.chat;

import de.nucleus.NucleusPlugin;
import de.nucleus.data.YamlFile;
import de.nucleus.util.TagColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tag-Definitionen in plugins/Nucleus/tags.yml. Jeder Tag wird einmal zu einer Component gerendert und
 * zwischengespeichert, weil der Chat (asynchron) ihn bei jeder Nachricht braucht. Änderungen nur auf dem
 * Hauptthread, gelesen wird thread-sicher.
 */
public final class TagStore {

    /** Erlaubte Formatierungen. */
    static final List<String> DECORATIONS = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

    /**
     * Ein Tag. Farben sind bereits normalisiert (siehe {@link TagColors}).
     */
    public record Tag(String id, String text, String bracketOpen, String bracketClose, String bracketColor,
                      String textColor, Set<String> decorations, boolean selectable) {

        Tag withText(String t) {
            return new Tag(id, t, bracketOpen, bracketClose, bracketColor, textColor, decorations, selectable);
        }

        Tag withBrackets(String open, String close) {
            return new Tag(id, text, open, close, bracketColor, textColor, decorations, selectable);
        }

        Tag withColors(String bracket, String textC) {
            return new Tag(id, text, bracketOpen, bracketClose, bracket, textC, decorations, selectable);
        }

        Tag withDecorations(Set<String> d) {
            return new Tag(id, text, bracketOpen, bracketClose, bracketColor, textColor, Set.copyOf(d), selectable);
        }

        Tag withSelectable(boolean s) {
            return new Tag(id, text, bracketOpen, bracketClose, bracketColor, textColor, decorations, s);
        }

        /** Permission, mit der ein Spieler diesen Tag auch ohne "selectable" wählen darf. */
        String permission() {
            return "nucleus.tag." + id;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NucleusPlugin plugin;
    private final YamlFile file;
    private final Map<String, Tag> tags = new ConcurrentHashMap<>();
    private final Map<String, Component> rendered = new ConcurrentHashMap<>();

    TagStore(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.file = new YamlFile(plugin.io(), plugin.getDataFolder().toPath().resolve("tags.yml"));
    }

    /** Lädt asynchron; danach (Hauptthread) wird {@code then} ausgeführt, z. B. Tab-Liste auffrischen. */
    void load(Runnable then) {
        file.load().whenComplete((yaml, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "tags.yml konnte nicht gelesen werden", error);
                return;
            }
            tags.clear();
            rendered.clear();
            ConfigurationSection sec = yaml.getConfigurationSection("tags");
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    ConfigurationSection s = sec.getConfigurationSection(id);
                    Tag tag = read(id.toLowerCase(Locale.ROOT), s);
                    if (tag != null) {
                        tags.put(tag.id(), tag);
                    }
                }
            }
            then.run();
        }));
    }

    /** Liest einen Tag aus einem YAML-Abschnitt (auch für den TagPlugin-Import); null wenn unbrauchbar. */
    static @Nullable Tag read(String id, @Nullable ConfigurationSection s) {
        if (s == null) {
            return null;
        }
        String bracket = TagColors.normalize(s.getString("bracket-color", "dark_gray")).orElse("dark_gray");
        String text = TagColors.normalize(s.getString("text-color", "white")).orElse("white");
        Set<String> decorations = new LinkedHashSet<>();
        for (String d : s.getStringList("decorations")) {
            String lower = d.toLowerCase(Locale.ROOT);
            if (DECORATIONS.contains(lower)) {
                decorations.add(lower);
            }
        }
        return new Tag(id, s.getString("text", id), s.getString("bracket-open", ""), s.getString("bracket-close", ""),
                bracket, text, Set.copyOf(decorations), s.getBoolean("selectable", false));
    }

    public @Nullable Tag get(String id) {
        return id == null ? null : tags.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Tag> all() {
        List<Tag> list = new ArrayList<>(tags.values());
        list.sort(java.util.Comparator.comparing(Tag::id));
        return list;
    }

    /** Fertig gerenderter Tag, null wenn es ihn nicht (mehr) gibt. Thread-sicher. */
    public @Nullable Component component(@Nullable String id) {
        Tag tag = get(id);
        return tag == null ? null : rendered.computeIfAbsent(tag.id(), k -> render(tag));
    }

    void put(Tag tag) {
        tags.put(tag.id(), tag);
        rendered.remove(tag.id());
        save();
    }

    boolean remove(String id) {
        boolean removed = tags.remove(id.toLowerCase(Locale.ROOT)) != null;
        rendered.remove(id.toLowerCase(Locale.ROOT));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Klammern in Klammerfarbe, Text in Textfarbe mit Formatierung. Texte werden nie als MiniMessage gelesen. */
    static Component render(Tag tag) {
        StringBuilder decoOpen = new StringBuilder();
        tag.decorations().forEach(d -> decoOpen.append('<').append(d).append('>'));
        Component open = tag.bracketOpen().isEmpty() ? Component.empty()
                : MM.deserialize(TagColors.openTag(tag.bracketColor()) + "<v>", Placeholder.unparsed("v", tag.bracketOpen()));
        Component text = MM.deserialize(TagColors.openTag(tag.textColor()) + decoOpen + "<v>",
                Placeholder.unparsed("v", tag.text()));
        Component close = tag.bracketClose().isEmpty() ? Component.empty()
                : MM.deserialize(TagColors.openTag(tag.bracketColor()) + "<v>", Placeholder.unparsed("v", tag.bracketClose()));
        return Component.textOfChildren(open, text, close);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Nucleus-Tags. Bearbeiten am besten mit /tag (danach gilt die Änderung sofort).",
                "Farben: gold, #FF55AA, gradient:#ff0000:#0000ff oder rainbow.",
                "decorations: bold, italic, underlined, strikethrough, obfuscated"));
        ConfigurationSection sec = yaml.createSection("tags");
        for (Tag t : all()) {
            ConfigurationSection s = sec.createSection(t.id());
            s.set("text", t.text());
            s.set("bracket-open", t.bracketOpen());
            s.set("bracket-close", t.bracketClose());
            s.set("bracket-color", t.bracketColor());
            s.set("text-color", t.textColor());
            s.set("decorations", new ArrayList<>(t.decorations()));
            s.set("selectable", t.selectable());
        }
        file.save(yaml);
    }
}
