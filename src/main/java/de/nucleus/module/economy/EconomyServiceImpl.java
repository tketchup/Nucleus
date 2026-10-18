package de.nucleus.module.economy;

import de.nucleus.api.EconomyResult;
import de.nucleus.api.EconomyService;
import de.nucleus.util.Money;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Implementierung der öffentlichen API; delegiert an das {@link Ledger}. */
final class EconomyServiceImpl implements EconomyService {

    private final EconomyModule module;
    private final Ledger ledger;

    private volatile List<BalanceEntry> topCache = List.of();
    private volatile long topCachedAt;

    EconomyServiceImpl(EconomyModule module, Ledger ledger) {
        this.module = module;
        this.ledger = ledger;
    }

    @Override
    public int decimals() {
        return module.decimals();
    }

    @Override
    public String currencySingular() {
        return module.settings().singular();
    }

    @Override
    public String currencyPlural() {
        return module.settings().plural();
    }

    @Override
    public String symbol() {
        return module.settings().symbol();
    }

    @Override
    public String format(long amount) {
        EconomyModule.Settings s = module.settings();
        String currency = amount == Money.one(decimals()) ? s.singular() : s.plural();
        return s.format()
                .replace("<amount>", Money.format(amount, decimals()))
                .replace("<symbol>", s.symbol())
                .replace("<currency>", currency);
    }

    @Override
    public OptionalLong parse(String input) {
        return Money.parse(input, decimals());
    }

    @Override
    public boolean hasAccount(UUID player) {
        return ledger.hasAccount(player);
    }

    @Override
    public long balance(UUID player) {
        return ledger.balance(player);
    }

    @Override
    public boolean has(UUID player, long amount) {
        return ledger.balance(player) >= amount;
    }

    @Override
    public long startingBalance() {
        return module.settings().startBalance();
    }

    @Override
    public void ensureAccount(UUID player, String name) {
        ledger.ensure(player, name, module.settings().startBalance());
    }

    @Override
    public EconomyResult deposit(UUID player, long amount) {
        return ledger.deposit(player, amount);
    }

    @Override
    public EconomyResult withdraw(UUID player, long amount) {
        return ledger.withdraw(player, amount);
    }

    @Override
    public EconomyResult set(UUID player, long amount) {
        return ledger.set(player, amount);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, long amount) {
        return ledger.transfer(from, to, amount);
    }

    @Override
    public List<BalanceEntry> top(int limit) {
        List<BalanceEntry> all = sortedCached();
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** Sortierte Liste aller Konten, höchstens cache-seconds alt. */
    List<BalanceEntry> sortedCached() {
        long now = System.currentTimeMillis();
        if (now - topCachedAt > module.settings().cacheMillis()) {
            topCache = List.copyOf(ledger.sorted());
            topCachedAt = now;
        }
        return topCache;
    }

    void invalidateTop() {
        topCachedAt = 0;
    }
}
