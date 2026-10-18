package de.nucleus.util;

import java.util.Map;

/**
 * Wandelt klassische Farbcodes ({@code &a}, {@code &l}, {@code &#ff8800}) in MiniMessage-Tags um,
 * damit Spieler beide Schreibweisen benutzen können. Ohne Adventure-Abhängigkeit, damit testbar.
 */
public final class LegacyCodes {

    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"), Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>"));

    private LegacyCodes() {
    }

    public static String toMiniMessage(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char next = Character.toLowerCase(input.charAt(i + 1));
                // &#rrggbb
                if (next == '#' && i + 8 <= input.length() && isHex(input, i + 2, i + 8)) {
                    out.append("<#").append(input, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }
                String tag = CODES.get(next);
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
