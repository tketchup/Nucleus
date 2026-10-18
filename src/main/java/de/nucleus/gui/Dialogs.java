package de.nucleus.gui;

import de.nucleus.NucleusPlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Native Minecraft-Dialoge (Paper Dialog-API) für Texteingaben und Bestätigungen. Die Antworten
 * kommen über Klick-Callbacks; sie werden hier immer auf den Hauptthread geholt.
 */
public final class Dialogs {

    private static final String INPUT = "value";
    /** Callbacks verfallen nach 5 Minuten oder nach einer Benutzung. */
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(5)).build();

    private Dialogs() {
    }

    /** Fragt einen Text ab. onSubmit läuft auf dem Hauptthread, nur bei "OK". */
    public static void askText(NucleusPlugin plugin, Player player, Component title, Component label,
                               String initial, int maxLength, Consumer<String> onSubmit) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .inputs(List.of(DialogInput.text(INPUT, label).initial(initial).maxLength(maxLength).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(plugin.messages().get("gui.dialog-ok"))
                                .action(DialogAction.customClick((response, audience) -> {
                                    String text = response.getText(INPUT);
                                    plugin.sync(() -> onSubmit.accept(text == null ? "" : text.trim()));
                                }, ONCE))
                                .build(),
                        ActionButton.builder(plugin.messages().get("gui.dialog-cancel")).build())));
        player.showDialog(dialog);
    }

    /** Ja/Nein-Abfrage. onConfirm läuft auf dem Hauptthread, nur bei "Ja". */
    public static void confirm(NucleusPlugin plugin, Player player, Component title, Runnable onConfirm) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(plugin.messages().get("gui.dialog-yes"))
                                .action(DialogAction.customClick((response, audience) -> plugin.sync(onConfirm), ONCE))
                                .build(),
                        ActionButton.builder(plugin.messages().get("gui.dialog-no")).build())));
        player.showDialog(dialog);
    }
}
