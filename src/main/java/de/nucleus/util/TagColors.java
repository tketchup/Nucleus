package de.nucleus.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Farbangaben für Tags: Minecraft-Farbname ({@code gold}), Hex ({@code #FF55AA}), Verlauf
 * ({@code gradient:#ff0000:#0000ff}, beliebig viele Stopps) oder {@code rainbow}. Spitze Klammern wie bei
 * MiniMessage ({@code <gold>}) werden akzeptiert. Ohne Adventure-Abhängigkeit, damit testbar.
 */
public final class TagColors {

    /** Die 16 Minecraft-Farbnamen (wie in MiniMessage). */
    private static final Set<String> NAMES = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private TagColors() {
    }

    /** Einheitliche Schreibweise oder leer, wenn ungültig. */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("<") && value.endsWith(">")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("rainbow")) {
            return Optional.of(value);
        }
        if (value.startsWith("gradient:")) {
            String[] parts = value.substring("gradient:".length()).split(":");
            if (parts.length < 2) {
                return Optional.empty();
            }
            List<String> stops = new ArrayList<>();
            for (String part : parts) {
                Optional<String> single = single(part);
                if (single.isEmpty()) {
                    return Optional.empty();
                }
                stops.add(single.get());
            }
            return Optional.of("gradient:" + String.join(":", stops));
        }
        return single(value);
    }

    /** Öffnender MiniMessage-Tag zu einer normalisierten Farbe, z. B. "&lt;gold&gt;" oder "&lt;#FF55AA&gt;". */
    public static String openTag(String normalized) {
        return "<" + normalized + ">";
    }

    /** Vorschläge für die Tab-Completion. */
    public static List<String> suggestions() {
        List<String> list = new ArrayList<>(NAMES);
        list.add("#");
        list.add("gradient:");
        list.add("rainbow");
        return list;
    }

    private static Optional<String> single(String value) {
        if (value.startsWith("#")) {
            return HEX.matcher(value).matches() ? Optional.of(value.toUpperCase(Locale.ROOT)) : Optional.empty();
        }
        return NAMES.contains(value) ? Optional.of(value) : Optional.empty();
    }
}
