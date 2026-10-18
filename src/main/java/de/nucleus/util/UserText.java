package de.nucleus.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.permissions.Permissible;

import java.util.ArrayList;
import java.util.List;

/**
 * Wandelt Spielertext (Nicknames, Chat, /msg) in Components um. Was erlaubt ist, hängt an
 * Permissions unter einem Präfix, z. B. "nucleus.chat" oder "nucleus.nick":
 * <ul>
 *   <li>{@code .color}: benannte Farben ({@code <red>}, {@code &c})</li>
 *   <li>{@code .hex}: Hex-Farben ({@code <#ff8800>}, {@code &#ff8800}), sonst nächste benannte Farbe</li>
 *   <li>{@code .format}: fett, kursiv, unterstrichen ... und {@code <reset>}</li>
 *   <li>{@code .minimessage}: zusätzlich Verläufe, Regenbogen, Übergänge, Schatten</li>
 * </ul>
 * Klick-, Hover- und Auswahl-Tags gibt es für Spieler nie.
 */
public final class UserText {

    private UserText() {
    }

    /** Hat der Spieler überhaupt eine Formatierungs-Permission? */
    public static boolean mayFormat(Permissible who, String permissionPrefix) {
        return who.hasPermission(permissionPrefix + ".color") || who.hasPermission(permissionPrefix + ".hex")
                || who.hasPermission(permissionPrefix + ".format") || who.hasPermission(permissionPrefix + ".minimessage");
    }

    /** Thread-sicher (auch im asynchronen Chat nutzbar). */
    public static Component parse(String input, Permissible who, String permissionPrefix) {
        boolean color = who.hasPermission(permissionPrefix + ".color");
        boolean hex = who.hasPermission(permissionPrefix + ".hex");
        boolean format = who.hasPermission(permissionPrefix + ".format");
        boolean mini = who.hasPermission(permissionPrefix + ".minimessage");
        if (!color && !hex && !format && !mini) {
            return Component.text(input);
        }
        List<TagResolver> tags = new ArrayList<>();
        if (color || hex || mini) {
            tags.add(StandardTags.color());
        }
        if (format || mini) {
            tags.add(StandardTags.decorations());
            tags.add(StandardTags.reset());
        }
        if (mini) {
            tags.add(StandardTags.gradient());
            tags.add(StandardTags.rainbow());
            tags.add(StandardTags.transition());
            tags.add(StandardTags.shadowColor());
            tags.add(StandardTags.pride());
        }
        MiniMessage mm = MiniMessage.builder().tags(TagResolver.resolver(tags)).build();
        Component result = mm.deserialize(LegacyCodes.toMiniMessage(input));
        if (!hex && !mini) {
            result = downgradeColors(result);
        }
        return result;
    }

    /** Ersetzt Hex-Farben durch die nächstgelegene der 16 Standardfarben (rekursiv). */
    private static Component downgradeColors(Component component) {
        TextColor c = component.color();
        Component out = component;
        if (c != null && !(c instanceof NamedTextColor)) {
            out = out.color(NamedTextColor.nearestTo(c));
        }
        List<Component> children = new ArrayList<>(out.children().size());
        for (Component child : out.children()) {
            children.add(downgradeColors(child));
        }
        return out.children(children);
    }
}
