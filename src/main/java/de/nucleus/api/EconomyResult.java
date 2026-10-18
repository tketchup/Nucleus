package de.nucleus.api;

/**
 * Ergebnis einer Economy-Operation.
 *
 * @param status  was passiert ist
 * @param balance Kontostand danach (bei transfer: der des Absenders); bei Fehlern der unveränderte Stand
 */
public record EconomyResult(Status status, long balance) {

    public enum Status {
        SUCCESS,
        /** Nicht genug Guthaben. */
        INSUFFICIENT_FUNDS,
        /** Höchststand des Empfängers würde überschritten. */
        LIMIT_EXCEEDED,
        /** Betrag negativ (oder 0 bei Zahlungen). */
        INVALID_AMOUNT,
        /** Konto existiert nicht. */
        NO_ACCOUNT
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }
}
