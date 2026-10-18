package de.nucleus.util;

import de.nucleus.util.SafeLocationFinder.BlockKind;
import de.nucleus.util.SafeLocationFinder.Position;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLocationFinderTest {

    /** Testwelt: alles Luft, außer explizit gesetzten Blöcken. Höhe 0 bis 64. */
    private static final class FakeWorld implements SafeLocationFinder.BlockView {
        private final Map<String, BlockKind> blocks = new HashMap<>();

        FakeWorld set(int x, int y, int z, BlockKind kind) {
            blocks.put(x + "," + y + "," + z, kind);
            return this;
        }

        /** Boden aus festen Blöcken in Höhe y über einen Bereich. */
        FakeWorld floor(int y, int from, int to, BlockKind kind) {
            for (int x = from; x <= to; x++) {
                for (int z = from; z <= to; z++) {
                    set(x, y, z, kind);
                }
            }
            return this;
        }

        @Override
        public BlockKind kind(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, BlockKind.EMPTY);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 64;
        }
    }

    @Test
    void keepsAlreadySafeTarget() {
        FakeWorld w = new FakeWorld().floor(10, -5, 5, BlockKind.SOLID);
        assertEquals(Optional.of(new Position(0, 11, 0)), SafeLocationFinder.find(w, 0, 11, 0, 3, 8));
    }

    @Test
    void movesUpOutOfSolidBlock() {
        // Ziel steckt im Boden (Füße in Stein): eine Ebene höher ist frei
        FakeWorld w = new FakeWorld().floor(10, -5, 5, BlockKind.SOLID).floor(11, -5, 5, BlockKind.SOLID);
        assertEquals(Optional.of(new Position(0, 12, 0)), SafeLocationFinder.find(w, 0, 11, 0, 3, 8));
    }

    @Test
    void rejectsBlockInFace() {
        FakeWorld w = new FakeWorld().floor(10, -5, 5, BlockKind.SOLID).set(0, 12, 0, BlockKind.SOLID);
        assertFalse(SafeLocationFinder.isSafe(w, 0, 11, 0));
    }

    @Test
    void neverStandsInOrOnLava() {
        FakeWorld w = new FakeWorld().floor(10, -5, 5, BlockKind.SOLID).set(0, 11, 0, BlockKind.HAZARD);
        assertFalse(SafeLocationFinder.isSafe(w, 0, 11, 0), "Füße in Lava");
        assertFalse(SafeLocationFinder.isSafe(w, 0, 12, 0), "auf Lava stehen");
        // Gefunden wird ein sicherer Nachbarblock
        Position p = SafeLocationFinder.find(w, 0, 11, 0, 3, 8).orElseThrow();
        assertTrue(SafeLocationFinder.isSafe(w, p.x(), p.y(), p.z()));
        assertFalse(p.x() == 0 && p.z() == 0);
    }

    @Test
    void dropsDownToGroundInsteadOfFalling() {
        // Ziel 40 Blöcke über dem Boden: es wird der Boden darunter gewählt, nicht die Luft
        FakeWorld w = new FakeWorld().floor(5, -5, 5, BlockKind.SOLID);
        assertEquals(Optional.of(new Position(0, 6, 0)), SafeLocationFinder.find(w, 0, 46, 0, 0, 8));
    }

    @Test
    void voidHasNoSafeSpot() {
        FakeWorld w = new FakeWorld();
        assertTrue(SafeLocationFinder.find(w, 0, 30, 0, 3, 8).isEmpty());
    }

    @Test
    void waterBreaksFallAndIsStandable() {
        FakeWorld w = new FakeWorld().set(0, 10, 0, BlockKind.SOLID).set(0, 11, 0, BlockKind.LIQUID);
        assertTrue(SafeLocationFinder.isSafe(w, 0, 11, 0));
        // Kopf unter Wasser ist nicht sicher
        w.set(0, 12, 0, BlockKind.LIQUID);
        assertFalse(SafeLocationFinder.isSafe(w, 0, 11, 0));
    }

    @Test
    void respectsWorldHeight() {
        FakeWorld w = new FakeWorld().floor(62, -1, 1, BlockKind.SOLID);
        // y=63: Kopf wäre bei 64 = außerhalb (maxY exklusiv)
        assertFalse(SafeLocationFinder.isSafe(w, 0, 63, 0));
        // Ziel unterhalb der Welt wird auf gültige Höhe geklemmt
        FakeWorld low = new FakeWorld().floor(0, -1, 1, BlockKind.SOLID);
        assertEquals(Optional.of(new Position(0, 1, 0)), SafeLocationFinder.find(low, 0, -20, 0, 0, 8));
    }

    /** Großer Lavasee (y=20) über einer Höhle (y=10..18): nie in die Höhle, lieber abbrechen. */
    @Test
    void neverDropsThroughLavaLakeIntoCave() {
        FakeWorld w = new FakeWorld()
                .floor(9, -10, 10, BlockKind.SOLID)     // Höhlenboden
                .floor(19, -10, 10, BlockKind.SOLID)    // Höhlendecke
                .floor(20, -10, 10, BlockKind.HAZARD);  // Lavasee
        assertTrue(SafeLocationFinder.find(w, 0, 25, 0, 3, 8).isEmpty());
    }

    /** Kleiner Lavasee: man landet am Ufer auf der Oberfläche, nicht darunter. */
    @Test
    void landsOnShoreOfSmallLavaLake() {
        FakeWorld w = new FakeWorld()
                .floor(9, -10, 10, BlockKind.SOLID)
                .floor(19, -10, 10, BlockKind.SOLID)
                .floor(20, -10, 10, BlockKind.SOLID)    // Oberfläche
                .floor(20, -2, 2, BlockKind.HAZARD);    // Lava nur in der Mitte
        Position p = SafeLocationFinder.find(w, 0, 25, 0, 3, 8).orElseThrow();
        assertEquals(21, p.y(), "auf der Oberfläche");
        assertEquals(3, Math.max(Math.abs(p.x()), Math.abs(p.z())), "erster Ring außerhalb der Lava");
    }

    /** Ziel im Fels mit Höhle darunter: nach oben an die Oberfläche, nicht in die Höhle. */
    @Test
    void buriedTargetGoesUpNotIntoCaveBelow() {
        FakeWorld w = new FakeWorld();
        for (int y = 1; y <= 30; y++) {
            w.set(0, y, 0, BlockKind.SOLID);
        }
        w.set(0, 25, 0, BlockKind.EMPTY).set(0, 26, 0, BlockKind.EMPTY); // Höhle direkt darunter
        assertEquals(Optional.of(new Position(0, 31, 0)), SafeLocationFinder.find(w, 0, 28, 0, 0, 8));
    }

    /** Ziel in der Luft über einem See: Landung im Wasser an der Oberfläche. */
    @Test
    void fallsIntoWaterSurface() {
        FakeWorld w = new FakeWorld().set(0, 10, 0, BlockKind.SOLID)
                .set(0, 11, 0, BlockKind.LIQUID).set(0, 12, 0, BlockKind.LIQUID);
        assertEquals(Optional.of(new Position(0, 12, 0)), SafeLocationFinder.find(w, 0, 30, 0, 0, 8));
        // Ziel unter Wasser: hoch zur Oberfläche
        assertEquals(Optional.of(new Position(0, 12, 0)), SafeLocationFinder.find(w, 0, 11, 0, 0, 8));
    }

    @Test
    void prefersTargetColumnOverNeighbours() {
        FakeWorld w = new FakeWorld().floor(10, -5, 5, BlockKind.SOLID).set(0, 13, 0, BlockKind.SOLID);
        // Spalte 0,0: y=11 Kopf frei? Kopf bei 12 frei -> sicher; der Block bei 13 stört nicht
        assertEquals(Optional.of(new Position(0, 11, 0)), SafeLocationFinder.find(w, 0, 11, 0, 3, 8));
    }
}
