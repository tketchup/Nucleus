package de.nucleus.api;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Öffentliche Economy-Schnittstelle von Nucleus für andere (eigene) Plugins.
 *
 * <p>Abrufen, solange das economy-Modul aktiv ist:
 * <pre>{@code
 * EconomyService eco = Bukkit.getServicesManager().load(EconomyService.class);
 * if (eco != null) {
 *     long cents = eco.parse("12,50").orElseThrow();
 *     EconomyResult r = eco.withdraw(player.getUniqueId(), cents);
 * }
 * }</pre>
 *
 * <p><b>Beträge</b> sind immer {@code long} in der kleinsten Einheit (bei 2 Nachkommastellen: Cent).
 * Es gibt keine Gleitkommazahlen und damit keine Rundungsfehler. Kontostände sind nie negativ.
 *
 * <p><b>Threads:</b> Alle Methoden sind threadsicher und blockieren nicht (kein Datei- oder Netzwerkzugriff).
 */
public interface EconomyService {

    /** Anzahl Nachkommastellen (0 bis 4). */
    int decimals();

    String currencySingular();

    String currencyPlural();

    String symbol();

    /** Formatiert z. B. 123456 als "1.234,56 €" (nach dem Format in der config.yml). */
    String format(long amount);

    /** Liest "12", "12,5", "12.50", "1k", "2,5m". Leer bei ungültiger Eingabe oder zu vielen Nachkommastellen. */
    OptionalLong parse(String input);

    boolean hasAccount(UUID player);

    /** Kontostand, 0 wenn es kein Konto gibt. */
    long balance(UUID player);

    boolean has(UUID player, long amount);

    /** Startguthaben neuer Konten (kleinste Einheit). */
    long startingBalance();

    /** Legt das Konto mit dem Startguthaben an, falls es noch nicht existiert. */
    void ensureAccount(UUID player, String name);

    EconomyResult deposit(UUID player, long amount);

    EconomyResult withdraw(UUID player, long amount);

    EconomyResult set(UUID player, long amount);

    /** Überweisung als eine atomare Operation: entweder beides oder nichts. */
    EconomyResult transfer(UUID from, UUID to, long amount);

    /** Reichste Spieler, absteigend. Kann bis zu einige Sekunden alt sein (Cache). */
    List<BalanceEntry> top(int limit);

    /** Ein Eintrag der Bestenliste. */
    record BalanceEntry(UUID player, String name, long balance) {
    }
}
