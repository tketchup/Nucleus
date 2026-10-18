package de.nucleus.core;

import io.papermc.paper.command.brigadier.Commands;

/**
 * Ein abschaltbares Modul. Implementierungen brauchen einen öffentlichen Konstruktor
 * {@code (NucleusPlugin)}; sie werden erst per Reflection geladen, wenn sie aktiviert sind.
 */
public interface Module {

    /** Listener, Tasks und Daten starten. Wird auch nach /nucleus reload erneut aufgerufen. */
    void enable();

    /** Alles aus {@link #enable()} wieder abbauen und offene Daten speichern. */
    void disable();

    /**
     * Befehle beim Brigadier-Registrar anmelden. Wird nur für aktive Module aufgerufen, beim Start
     * und bei jedem Neuladen der Datapacks (/minecraft:reload).
     */
    void registerCommands(Commands registrar);
}
