package de.nucleus.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Items für Menüs und die Umrechnung zwischen Item und gespeichertem Schlüssel ("minecraft:red_bed"). */
public final class Icons {

    private Icons() {
    }

    /** Item mit Namen und Beschreibung, ohne Vanilla-Kursivschrift. */
    public static ItemStack icon(ItemType type, Component name, List<Component> lore) {
        ItemStack stack = type.createItemStack(1);
        stack.setData(DataComponentTypes.CUSTOM_NAME, plain(name));
        if (!lore.isEmpty()) {
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore.stream().map(Icons::plain).toList()));
        }
        return stack;
    }

    public static ItemStack icon(String key, ItemType fallback, Component name, List<Component> lore) {
        ItemType type = type(key);
        return icon(type != null && type != ItemType.AIR ? type : fallback, name, lore);
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Item-Typ zu einem gespeicherten Schlüssel, null wenn unbekannt. */
    public static @Nullable ItemType type(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM).get(Key.key(key));
        } catch (InvalidKeyException e) {
            return null;
        }
    }

    /** Schlüssel eines Items, z. B. "minecraft:blaze_rod". */
    @SuppressWarnings("deprecation") // ItemStack#getType liefert in 26.3 noch Material; asItemType() führt zum neuen ItemType
    public static String key(ItemStack stack) {
        ItemType type = stack.getType().asItemType();
        return type == null ? "minecraft:air" : type.key().asString();
    }
}
