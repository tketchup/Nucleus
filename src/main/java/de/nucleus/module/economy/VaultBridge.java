package de.nucleus.module.economy;

import de.nucleus.api.EconomyResult;
import de.nucleus.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Gemeinsame Logik für beide Vault-Schnittstellen (VaultUnlocked "modern" und klassisches Vault).
 * Kennt selbst keine Vault-Klassen. Beträge kommen als BigDecimal und werden so gerundet, dass nie
 * Geld entsteht: Einzahlungen abrunden, Abbuchungen aufrunden.
 */
final class VaultBridge {

    /** Ergebnis einer Buchung für die Adapter. */
    record Result(boolean success, BigDecimal amount, BigDecimal balance, String error) {
    }

    static final String NAME = "Nucleus";

    private final EconomyModule module;

    VaultBridge(EconomyModule module) {
        this.module = module;
    }

    boolean enabled() {
        return module.loaded();
    }

    int decimals() {
        return module.decimals();
    }

    String singular() {
        return module.settings().singular();
    }

    String plural() {
        return module.settings().plural();
    }

    String format(BigDecimal amount) {
        OptionalLong minor = Money.fromDecimal(amount.abs(), decimals(), RoundingMode.HALF_UP);
        String text = minor.isPresent() ? module.service().format(minor.getAsLong()) : amount.toPlainString();
        return amount.signum() < 0 ? "-" + text : text;
    }

    /** Nur eine Währung: null, leer, "default" oder einer der beiden Namen. */
    boolean isOurCurrency(@Nullable String currency) {
        return currency == null || currency.isBlank() || currency.equalsIgnoreCase("default")
                || currency.equalsIgnoreCase(singular()) || currency.equalsIgnoreCase(plural());
    }

    boolean hasAccount(UUID id) {
        return module.loaded() && module.ledger().hasAccount(id);
    }

    boolean createAccount(UUID id, String name) {
        if (!module.loaded()) {
            return false;
        }
        module.service().ensureAccount(id, name == null ? "?" : name);
        return true;
    }

    Map<UUID, String> names() {
        return module.ledger().names();
    }

    boolean rename(UUID id, String name) {
        return module.ledger().rename(id, name);
    }

    BigDecimal balance(UUID id) {
        return Money.toDecimal(module.ledger().balance(id), decimals());
    }

    boolean has(UUID id, BigDecimal amount) {
        OptionalLong minor = Money.fromDecimal(amount, decimals(), RoundingMode.UP);
        return minor.isPresent() && module.ledger().balance(id) >= minor.getAsLong();
    }

    Result withdraw(UUID id, BigDecimal amount) {
        OptionalLong minor = Money.fromDecimal(amount, decimals(), RoundingMode.UP);
        if (minor.isEmpty()) {
            return fail(id, amount, "Ungültiger Betrag");
        }
        return result(module.ledger().withdraw(id, minor.getAsLong()), amount);
    }

    Result deposit(UUID id, BigDecimal amount) {
        OptionalLong minor = Money.fromDecimal(amount, decimals(), RoundingMode.DOWN);
        if (minor.isEmpty()) {
            return fail(id, amount, "Ungültiger Betrag");
        }
        return result(module.ledger().deposit(id, minor.getAsLong()), amount);
    }

    Result set(UUID id, BigDecimal amount) {
        OptionalLong minor = Money.fromDecimal(amount, decimals(), RoundingMode.DOWN);
        if (minor.isEmpty()) {
            return fail(id, amount, "Ungültiger Betrag");
        }
        return result(module.ledger().set(id, minor.getAsLong()), amount);
    }

    private Result result(EconomyResult r, BigDecimal amount) {
        if (r.success()) {
            module.service().invalidateTop();
        }
        String error = switch (r.status()) {
            case SUCCESS -> "";
            case INSUFFICIENT_FUNDS -> "Zu wenig Guthaben";
            case LIMIT_EXCEEDED -> "Höchststand erreicht";
            case INVALID_AMOUNT -> "Ungültiger Betrag";
            case NO_ACCOUNT -> "Kein Konto";
        };
        return new Result(r.success(), amount, Money.toDecimal(r.balance(), decimals()), error);
    }

    private Result fail(UUID id, BigDecimal amount, String error) {
        return new Result(false, amount, balance(id), error);
    }

    /** Klassisches Vault arbeitet oft mit Namen: auf UUID abbilden (ohne Netzwerkzugriff). */
    static @Nullable UUID uuidOf(@Nullable String name) {
        if (name == null) {
            return null;
        }
        OfflinePlayer online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }
}
