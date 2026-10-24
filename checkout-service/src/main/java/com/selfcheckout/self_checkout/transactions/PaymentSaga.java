package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.persistence.repository.SaleWriter;
import com.selfcheckout.self_checkout.persistence.repository.SaleWriter.PendingSale;
import com.selfcheckout.self_checkout.remote.InventoryClient;
import com.selfcheckout.self_checkout.remote.StockCommitOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

// CHECKOUT-SERVICE, transactions layer: the steps of the payment saga that
// talk to inventory, and the rules for retrying them. checkout-service is the
// ORCHESTRATOR: it calls, waits, and decides; inventory only ever answers.
//
//   commitStock   the PIVOT. Retried with the same transaction id until it
//                 answers or the time budget runs out. Retrying is safe because
//                 inventory's commit is idempotent on that id.
//   finishSale    tx B, after the pivot. A local write that cannot
//                 legitimately fail, so it is retried until it succeeds --
//                 after the pivot the saga only ever goes FORWARD (no undoing
//                 a stock commit that really happened).
//
// The outcome of commitStock decides what the customer sees:
//   committed                 -> finish the sale, 200 receipt
//   certainly not committed   -> undo tx A, reopen the basket, 503
//   unknown (no answer)       -> 503, basket stays COMPLETING, and the
//                                resolver keeps going until inventory answers
@Component
public class PaymentSaga {

    private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

    /** Inventory certainly did not commit: safe to reopen the basket. */
    static final class StockNotCommittedException extends RuntimeException {
        StockNotCommittedException(String m) { super(m); }
    }

    /** Inventory may or may not have committed: only a later answer can tell. */
    static final class StockOutcomeUnknownException extends RuntimeException {
        StockOutcomeUnknownException(String m) { super(m); }
    }

    private final InventoryClient inventory;
    private final SaleWriter sales;
    private final SagaCounters counters;
    private final boolean haltAfterStockCommit;

    public PaymentSaga(InventoryClient inventory, SaleWriter sales, SagaCounters counters,
                       // TEST HOOK, off by default: kill this process the instant
                       // inventory confirms a stock commit, before tx B -- the
                       // "crash mid-saga" failure test. Logged at startup and in
                       // every "saga |" line, so a load run records that it was off.
                       @Value("${selfcheckout.test.halt-after-stock-commit:false}") boolean haltAfterStockCommit) {
        this.inventory = inventory;
        this.sales = sales;
        this.counters = counters;
        this.haltAfterStockCommit = haltAfterStockCommit;
        counters.haltHook(haltAfterStockCommit);
        if (haltAfterStockCommit) {
            log.warn("TEST HOOK halt-after-stock-commit is ON: this process will halt after the first stock commit");
        } else {
            log.info("test hook halt-after-stock-commit is off");
        }
    }

    /**
     * Commit the stock for this sale, retrying until inventory answers or the
     * budget runs out. Returns units actually taken per SKU.
     *
     * @param budget null = no limit (the resolver, which must eventually finish)
     */
    Map<String, Integer> commitStock(PendingSale sale, Duration budget) {
        // Always the same lines in the same order, from the saved sale: that is
        // what inventory compares a retry against.
        Map<String, Integer> lines = new TreeMap<>(sale.lines());
        long deadline = budget == null ? Long.MAX_VALUE : System.nanoTime() + budget.toNanos();
        boolean possiblyDelivered = false;
        String lastReason = "";
        long backoffMillis = 50;
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                counters.stockRetries.incrementAndGet();
            }
            long t0 = System.nanoTime();
            StockCommitOutcome outcome = inventory.commit(sale.id(), lines);
            if (outcome instanceof StockCommitOutcome.Committed c) {
                counters.recordCall(System.nanoTime() - t0);
                if (c.replayed()) {
                    counters.replayed.incrementAndGet();
                }
                if (haltAfterStockCommit) {
                    log.error("TEST HOOK: halting after the stock commit of {}, before tx B", sale.id());
                    Runtime.getRuntime().halt(137);
                }
                return c.applied();
            }
            if (outcome instanceof StockCommitOutcome.Uncertain u) {
                possiblyDelivered = true;       // from now on we can never be sure it did NOT commit
                lastReason = u.reason();
            } else if (outcome instanceof StockCommitOutcome.NotDelivered n) {
                lastReason = n.reason();
            }
            long sleepNanos = Duration.ofMillis(backoffMillis).toNanos();
            if (System.nanoTime() + sleepNanos > deadline) {
                if (possiblyDelivered) {
                    throw new StockOutcomeUnknownException(lastReason);
                }
                throw new StockNotCommittedException(lastReason);
            }
            sleep(backoffMillis);
            backoffMillis = Math.min(backoffMillis * 2, 2_000);
        }
    }

    /**
     * tx B: record what inventory took and mark the sale COMPLETED. Retried
     * until it succeeds or the budget runs out (then the sale stays PENDING
     * and the resolver, or the next startup, finishes it).
     */
    void finishSale(String id, Map<String, Integer> applied, Duration budget) {
        long deadline = budget == null ? Long.MAX_VALUE : System.nanoTime() + budget.toNanos();
        long backoffMillis = 50;
        while (true) {
            try {
                sales.complete(id, applied);
                return;
            } catch (RuntimeException e) {
                counters.finishRetries.incrementAndGet();
                if (System.nanoTime() + Duration.ofMillis(backoffMillis).toNanos() > deadline) {
                    throw new StockOutcomeUnknownException("could not record the completed sale: " + e.getMessage());
                }
                log.warn("tx B for {} failed, retrying: {}", id, e.toString());
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, 2_000);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StockOutcomeUnknownException("interrupted");
        }
    }
}
