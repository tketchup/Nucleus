package de.nucleus.command;

/**
 * Bricht einen Befehl mit einer Nachricht aus messages.yml ab. Wird von {@link CommandKit#run}
 * abgefangen und an den Absender geschickt. Ohne Stacktrace, weil es ein normaler Ablauf ist.
 */
public final class CommandFail extends RuntimeException {

    private final String key;
    private final transient Object[] placeholders;

    public CommandFail(String key, Object... placeholders) {
        super(key, null, false, false);
        this.key = key;
        this.placeholders = placeholders;
    }

    public String key() {
        return key;
    }

    public Object[] placeholders() {
        return placeholders;
    }
}
