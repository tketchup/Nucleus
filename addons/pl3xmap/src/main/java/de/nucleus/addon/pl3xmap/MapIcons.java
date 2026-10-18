package de.nucleus.addon.pl3xmap;

import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.image.IconImage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Die Symbole werden im Code gezeichnet, damit das Add-on ohne Bilddateien auskommt. */
final class MapIcons {

    static final String WARP = "nucleus_warp";
    static final String SPAWN = "nucleus_spawn";
    static final String MOB_HOSTILE = "nucleus_mob_hostile";
    static final String MOB_PASSIVE = "nucleus_mob_passive";
    static final String MOB_OTHER = "nucleus_mob_other";

    private static final Color OUTLINE = new Color(0x20, 0x20, 0x20);

    private MapIcons() {
    }

    static void register() {
        var registry = Pl3xMap.api().getIconRegistry();
        registry.register(new IconImage(WARP, warp(), "png"));
        registry.register(new IconImage(SPAWN, spawn(), "png"));
        registry.register(new IconImage(MOB_HOSTILE, dot(new Color(0xFF, 0x55, 0x55)), "png"));
        registry.register(new IconImage(MOB_PASSIVE, dot(new Color(0x55, 0xFF, 0x55)), "png"));
        registry.register(new IconImage(MOB_OTHER, dot(new Color(0xFF, 0xDD, 0x44)), "png"));
    }

    /**
     * Registriert eine Minecraft-Textur als Kartensymbol, pixelgenau vergrößert (sonst wird sie im Browser unscharf).
     */
    static void registerTexture(String key, BufferedImage texture, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(texture, 0, 0, size, size, null);
        g.dispose();
        Pl3xMap.api().getIconRegistry().register(new IconImage(key, img, "png"));
    }

    private static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Warp: goldener Kreis mit weißem Kern. */
    private static BufferedImage warp() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.setColor(new Color(0xFF, 0xAA, 0x00));
        g.fillOval(3, 3, 26, 26);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(3, 3, 26, 26);
        g.setColor(Color.WHITE);
        g.fillOval(11, 11, 10, 10);
        g.dispose();
        return img;
    }

    /** Spawn: grünes Haus. */
    private static BufferedImage spawn() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        Polygon house = new Polygon(new int[]{16, 29, 25, 25, 7, 7, 3}, new int[]{3, 15, 15, 29, 29, 15, 15}, 7);
        g.setColor(new Color(0x55, 0xDD, 0x55));
        g.fillPolygon(house);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolygon(house);
        g.setColor(Color.WHITE);
        g.fillRect(13, 19, 6, 10);
        g.dispose();
        return img;
    }

    /** Mob: kleiner farbiger Punkt. */
    private static BufferedImage dot(Color color) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.setColor(color);
        g.fillOval(2, 2, 12, 12);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(2, 2, 12, 12);
        g.dispose();
        return img;
    }
}
