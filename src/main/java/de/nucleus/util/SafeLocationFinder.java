package de.nucleus.util;

import java.util.Optional;

/**
 * Sucht eine sichere Stehposition in der Nähe eines Ziels. Rein rechnerisch über {@link BlockView},
 * damit die Logik ohne Server testbar ist.
 *
 * <p>Sicher heißt: Füße frei (oder Wasser), Kopf frei, darunter fester Boden (oder Wasser, das den
 * Fall abfängt), nichts Gefährliches an Füßen, Kopf oder Boden und innerhalb der Welthöhe. Ohne
 * festen Boden wird nicht teleportiert, das verhindert Void- und Fallschaden-Tode.
 */
public final class SafeLocationFinder {

    /** Grobe Einordnung eines Blocks für die Sicherheitsprüfung. */
    public enum BlockKind {
        /** Begehbar und harmlos: Luft, Gras, Blumen, Fackeln ... */
        EMPTY,
        /** Wasser: man kann darin stehen, es bremst Stürze. */
        LIQUID,
        /** Fester Block, auf dem man stehen kann. */
        SOLID,
        /** Lava, Feuer, Magma, Kaktus, Portale ... weder darin noch darauf stehen. */
        HAZARD
    }

    /** Lesezugriff auf Blöcke einer Welt. */
    public interface BlockView {
        BlockKind kind(int x, int y, int z);

        /** Niedrigste baubare Höhe (inklusive). */
        int minY();

        /** Höchste baubare Höhe (exklusive), wie World#getMaxHeight. */
        int maxY();
    }

    /** Eine gefundene Blockposition (Füße des Spielers). */
    public record Position(int x, int y, int z) {
    }

    private SafeLocationFinder() {
    }

    public static boolean isSafe(BlockView view, int x, int y, int z) {
        if (y - 1 < view.minY() || y + 1 >= view.maxY()) {
            return false;
        }
        BlockKind feet = view.kind(x, y, z);
        BlockKind head = view.kind(x, y + 1, z);
        BlockKind ground = view.kind(x, y - 1, z);
        if (head != BlockKind.EMPTY) {
            return false; // kein Block (und kein Wasser) im Gesicht
        }
        return switch (feet) {
            case EMPTY -> ground == BlockKind.SOLID;
            case LIQUID -> ground == BlockKind.SOLID || ground == BlockKind.LIQUID;
            default -> false;
        };
    }

    /**
     * @param radius   wie viele Blöcke seitlich gesucht wird (0 = nur die Zielspalte)
     * @param vertical wie weit zuerst nach oben/unten abwechselnd gesucht wird
     */
    public static Optional<Position> find(BlockView view, int x, int y, int z, int radius, int vertical) {
        int startY = Math.clamp(y, view.minY() + 1, view.maxY() - 2);
        // Zuerst die Zielspalte, dann Ringe mit wachsendem Abstand
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // nur der Rand des Rings
                    }
                    Optional<Position> hit = searchColumn(view, x + dx, startY, z + dz, vertical);
                    if (hit.isPresent()) {
                        return hit;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Sucht in einer Spalte, ohne je durch Blöcke nach unten zu gehen. So landet man nie in einer Höhle
     * unter dem Ziel (z. B. unter einem Lavasee):
     * <ul>
     *   <li>Ziel in der Luft: nur durch Luft nach unten fallen bis zur ersten Oberfläche. Ist die
     *       gefährlich (Lava), ist die Spalte unbrauchbar.</li>
     *   <li>Ziel im Wasser: nach oben bis zur Wasseroberfläche.</li>
     *   <li>Ziel im Block: nach oben heraus (höchstens {@code vertical} Blöcke).</li>
     * </ul>
     */
    private static Optional<Position> searchColumn(BlockView view, int x, int y, int z, int vertical) {
        BlockKind feet = view.kind(x, y, z);
        if (feet == BlockKind.EMPTY) {
            int cy = y;
            while (cy - 1 > view.minY() && view.kind(x, cy - 1, z) == BlockKind.EMPTY) {
                cy--; // durch Luft fallen
            }
            if (isSafe(view, x, cy, z)) {
                return Optional.of(new Position(x, cy, z));
            }
            // Landung im Wasser: im Wasser stehen statt auf der Oberfläche
            if (view.kind(x, cy - 1, z) == BlockKind.LIQUID && isSafe(view, x, cy - 1, z)) {
                return Optional.of(new Position(x, cy - 1, z));
            }
        } else if (feet == BlockKind.LIQUID) {
            int cy = y;
            while (cy + 2 < view.maxY() && view.kind(x, cy + 1, z) == BlockKind.LIQUID) {
                cy++; // zur Wasseroberfläche
            }
            if (isSafe(view, x, cy, z)) {
                return Optional.of(new Position(x, cy, z));
            }
        }
        // Im Block, unter zu niedriger Decke oder im Wasser ohne Ausweg: nach oben heraus
        for (int d = 1; d <= vertical; d++) {
            if (isSafe(view, x, y + d, z)) {
                return Optional.of(new Position(x, y + d, z));
            }
        }
        return Optional.empty();
    }
}
