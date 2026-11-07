package de.nucleus.module.serverlist;

/**
 * Ungefähre Pixelbreite von Text in der Standardschrift, um MOTD-Zeilen mit vorangestellten Leerzeichen
 * zu zentrieren (die Serverliste zentriert nicht selbst). Ohne Bukkit, damit es testbar ist.
 */
final class MotdCentering {

    /** Breite eines Leerzeichens inklusive Abstand. */
    private static final int SPACE_WIDTH = 4;

    private MotdCentering() {
    }

    /** Breite eines Zeichens inklusive 1 px Abstand. */
    static int charWidth(int codePoint) {
        return switch (codePoint) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l' -> 3;
            case ' ', 'I', 't', '[', ']' -> 4;
            case 'f', 'k', '(', ')', '<', '>', '*', '{', '}' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    static int width(String plain) {
        return plain.codePoints().map(MotdCentering::charWidth).sum();
    }

    /** Leerzeichen, die vor die Zeile gehören, damit sie in {@code maxWidth} Pixeln mittig steht. */
    static int spaces(String plain, int maxWidth) {
        return Math.max(0, (maxWidth - width(plain)) / 2 / SPACE_WIDTH);
    }
}
