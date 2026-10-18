package de.nucleus.module.economy;

import de.nucleus.api.EconomyResult;
import de.nucleus.api.EconomyResult.Status;
import de.nucleus.api.EconomyService.BalanceEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kontobuch: alle Konten und alle Buchungen. Jede Methode ist {@code synchronized}, damit
 * Überweisungen atomar sind (kein Geld entsteht oder verschwindet, auch bei gleichzeitigen Zugriffen
 * aus mehreren Threads). Ohne Bukkit-Abhängigkeit, damit testbar.
 */
public final class Ledger {

    /** Ein Konto. Nur innerhalb des Ledger-Locks verändern. */
    static final class Account {
        long balance;
        String name;
        boolean acceptsPayments = true;

        Account(String name, long balance) {
            this.name = name;
            this.balance = balance;
        }
    }

    private final Map<UUID, Account> accounts = new HashMap<>();
    private long maxBalance;
    private boolean dirty;

    public Ledger(long maxBalance) {
        this.maxBalance = maxBalance;
    }

    public synchronized void maxBalance(long max) {
        maxBalance = max;
    }

    public synchronized boolean hasAccount(UUID id) {
        return accounts.containsKey(id);
    }

    /** @return true, wenn das Konto neu angelegt wurde */
    public synchronized boolean ensure(UUID id, String name, long startBalance) {
        Account a = accounts.get(id);
        if (a != null) {
            if (!a.name.equals(name)) {
                a.name = name;
                dirty = true;
            }
            return false;
        }
        accounts.put(id, new Account(name, Math.clamp(startBalance, 0, maxBalance)));
        dirty = true;
        return true;
    }

    public synchronized long balance(UUID id) {
        Account a = accounts.get(id);
        return a == null ? 0 : a.balance;
    }

    public synchronized EconomyResult deposit(UUID id, long amount) {
        Account a = accounts.get(id);
        if (a == null) {
            return new EconomyResult(Status.NO_ACCOUNT, 0);
        }
        if (amount < 0) {
            return new EconomyResult(Status.INVALID_AMOUNT, a.balance);
        }
        if (amount > maxBalance - a.balance) { // Form ohne Überlauf
            return new EconomyResult(Status.LIMIT_EXCEEDED, a.balance);
        }
        a.balance += amount;
        dirty = true;
        return new EconomyResult(Status.SUCCESS, a.balance);
    }

    public synchronized EconomyResult withdraw(UUID id, long amount) {
        Account a = accounts.get(id);
        if (a == null) {
            return new EconomyResult(Status.NO_ACCOUNT, 0);
        }
        if (amount < 0) {
            return new EconomyResult(Status.INVALID_AMOUNT, a.balance);
        }
        if (a.balance < amount) {
            return new EconomyResult(Status.INSUFFICIENT_FUNDS, a.balance);
        }
        a.balance -= amount;
        dirty = true;
        return new EconomyResult(Status.SUCCESS, a.balance);
    }

    public synchronized EconomyResult set(UUID id, long amount) {
        Account a = accounts.get(id);
        if (a == null) {
            return new EconomyResult(Status.NO_ACCOUNT, 0);
        }
        if (amount < 0) {
            return new EconomyResult(Status.INVALID_AMOUNT, a.balance);
        }
        if (amount > maxBalance) {
            return new EconomyResult(Status.LIMIT_EXCEEDED, a.balance);
        }
        a.balance = amount;
        dirty = true;
        return new EconomyResult(Status.SUCCESS, a.balance);
    }

    /** Atomar: entweder werden beide Konten geändert oder keins. */
    public synchronized EconomyResult transfer(UUID from, UUID to, long amount) {
        Account a = accounts.get(from);
        Account b = accounts.get(to);
        if (a == null || b == null) {
            return new EconomyResult(Status.NO_ACCOUNT, a == null ? 0 : a.balance);
        }
        if (amount <= 0 || from.equals(to)) {
            return new EconomyResult(Status.INVALID_AMOUNT, a.balance);
        }
        if (a.balance < amount) {
            return new EconomyResult(Status.INSUFFICIENT_FUNDS, a.balance);
        }
        if (amount > maxBalance - b.balance) {
            return new EconomyResult(Status.LIMIT_EXCEEDED, a.balance);
        }
        a.balance -= amount;
        b.balance += amount;
        dirty = true;
        return new EconomyResult(Status.SUCCESS, a.balance);
    }

    public synchronized boolean acceptsPayments(UUID id) {
        Account a = accounts.get(id);
        return a == null || a.acceptsPayments;
    }

    public synchronized void acceptsPayments(UUID id, boolean value) {
        Account a = accounts.get(id);
        if (a != null) {
            a.acceptsPayments = value;
            dirty = true;
        }
    }

    /** UUID → Name aller Konten (Kopie). */
    public synchronized Map<UUID, String> names() {
        Map<UUID, String> map = new HashMap<>(accounts.size());
        accounts.forEach((id, a) -> map.put(id, a.name));
        return map;
    }

    public synchronized boolean rename(UUID id, String name) {
        Account a = accounts.get(id);
        if (a == null) {
            return false;
        }
        a.name = name;
        dirty = true;
        return true;
    }

    /** Alle Konten nach Guthaben absteigend (bei Gleichstand nach Name). */
    public synchronized List<BalanceEntry> sorted() {
        List<BalanceEntry> list = new ArrayList<>(accounts.size());
        accounts.forEach((id, a) -> list.add(new BalanceEntry(id, a.name, a.balance)));
        list.sort(Comparator.comparingLong(BalanceEntry::balance).reversed()
                .thenComparing(BalanceEntry::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public synchronized long total() {
        long sum = 0;
        for (Account a : accounts.values()) {
            sum = sum > Long.MAX_VALUE - a.balance ? Long.MAX_VALUE : sum + a.balance;
        }
        return sum;
    }

    // --- Persistenz ---

    /** Momentaufnahme zum Speichern; setzt das Änderungs-Flag zurück. */
    synchronized Map<UUID, Account> snapshotAndClean() {
        Map<UUID, Account> copy = new HashMap<>(accounts.size());
        accounts.forEach((id, a) -> {
            Account c = new Account(a.name, a.balance);
            c.acceptsPayments = a.acceptsPayments;
            copy.put(id, c);
        });
        dirty = false;
        return copy;
    }

    synchronized boolean dirty() {
        return dirty;
    }

    /** Beim Laden: Konten aus der Datei übernehmen (vorhandene im Speicher gewinnen nicht). */
    synchronized void load(Map<UUID, Account> loaded) {
        accounts.putAll(loaded);
    }
}
