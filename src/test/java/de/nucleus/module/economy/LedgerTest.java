package de.nucleus.module.economy;

import de.nucleus.api.EconomyResult.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private Ledger ledger(long max) {
        Ledger l = new Ledger(max);
        l.ensure(a, "A", 1000);
        l.ensure(b, "B", 500);
        return l;
    }

    @Test
    void startBalanceOnlyOnce() {
        Ledger l = ledger(1_000_000);
        assertFalse(l.ensure(a, "A", 99999), "vorhandenes Konto wird nicht zurückgesetzt");
        assertEquals(1000, l.balance(a));
    }

    @Test
    void depositAndWithdraw() {
        Ledger l = ledger(1_000_000);
        assertEquals(Status.SUCCESS, l.deposit(a, 250).status());
        assertEquals(1250, l.balance(a));
        assertEquals(Status.SUCCESS, l.withdraw(a, 1250).status());
        assertEquals(0, l.balance(a));
        assertEquals(Status.INSUFFICIENT_FUNDS, l.withdraw(a, 1).status(), "nie negativ");
        assertEquals(Status.INVALID_AMOUNT, l.deposit(a, -5).status());
        assertEquals(Status.NO_ACCOUNT, l.deposit(UUID.randomUUID(), 5).status());
    }

    @Test
    void respectsMaxBalanceWithoutOverflow() {
        Ledger l = ledger(2000);
        assertEquals(Status.LIMIT_EXCEEDED, l.deposit(a, 1001).status());
        assertEquals(Status.SUCCESS, l.deposit(a, 1000).status());
        assertEquals(Status.LIMIT_EXCEEDED, l.deposit(a, Long.MAX_VALUE).status(), "kein long-Überlauf");
        assertEquals(Status.LIMIT_EXCEEDED, l.set(b, 2001).status());
    }

    @Test
    void transferIsAllOrNothing() {
        Ledger l = ledger(1200);
        assertEquals(Status.INSUFFICIENT_FUNDS, l.transfer(b, a, 501).status());
        assertEquals(Status.LIMIT_EXCEEDED, l.transfer(b, a, 300).status(), "Empfänger hätte 1300 > 1200");
        assertEquals(1000, l.balance(a), "nach Fehlschlag unverändert");
        assertEquals(500, l.balance(b));
        assertEquals(Status.SUCCESS, l.transfer(b, a, 200).status());
        assertEquals(1200, l.balance(a));
        assertEquals(300, l.balance(b));
        assertEquals(Status.INVALID_AMOUNT, l.transfer(a, a, 10).status(), "an sich selbst");
        assertEquals(Status.INVALID_AMOUNT, l.transfer(a, b, 0).status());
    }

    @Test
    void concurrentTransfersKeepTotal() throws InterruptedException {
        Ledger l = new Ledger(Long.MAX_VALUE);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            l.ensure(id, "P" + i, 10_000);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            int seed = t;
            pool.submit(() -> {
                start.await();
                java.util.Random rnd = new java.util.Random(seed);
                for (int i = 0; i < 20_000; i++) {
                    l.transfer(ids.get(rnd.nextInt(10)), ids.get(rnd.nextInt(10)), rnd.nextInt(500) + 1);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(100_000, l.total(), "kein Geld entstanden oder verschwunden");
        for (UUID id : ids) {
            assertTrue(l.balance(id) >= 0);
        }
    }

    @Test
    void sortedForBaltop() {
        Ledger l = ledger(1_000_000);
        UUID c = UUID.randomUUID();
        l.ensure(c, "C", 5000);
        var sorted = l.sorted();
        assertEquals(List.of("C", "A", "B"), sorted.stream().map(e -> e.name()).toList());
        assertEquals(6500, l.total());
    }
}
