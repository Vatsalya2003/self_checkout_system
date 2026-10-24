package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.persistence.repository.SaleWriter;
import com.selfcheckout.self_checkout.persistence.repository.SaleWriter.PendingSale;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

// CHECKOUT-SERVICE, transactions layer: finishes payments the request could
// not finish itself. This is the saga's FORWARD RECOVERY.
//
// Two ways a payment ends up here, both with a PENDING row in checkout_db:
//
//   1. Crash mid-saga. checkout-service died after writing tx A, maybe after
//      inventory committed the stock. On startup every PENDING row is queued.
//   2. No answer from inventory. A live payment's commit call timed out, so
//      inventory may already have taken the stock. The customer got 503; the
//      request hands the transaction id over and returns.
//
// For each one: send the same commit again (same transaction id, same lines,
// reloaded from the PENDING row), keep trying until inventory answers, then
// run tx B. Inventory's idempotency makes "again" safe: if it already
// committed, it returns what it stored and takes nothing more; if it never
// saw the request, it commits now. Either way both databases end up agreeing
// that the sale happened, and GET /transactions/{id} shows COMPLETED.
//
// Why forward and not "put the stock back" (compensation): once the stock
// commit may have happened, undoing it is a second distributed step that can
// itself fail halfway -- and a release can overtake the original commit while
// it is still in flight inside inventory. Retrying the same idempotent commit
// has neither problem.
//
// One background thread, unlimited retries with backoff. Live requests never
// work on the same transaction at the same time: a request only hands a
// payment over after it has stopped trying, and the rows found at startup
// belong to baskets that died with the previous process.
@Component
public class SagaResolver {

    private static final Logger log = LoggerFactory.getLogger(SagaResolver.class);

    private final SaleWriter sales;
    private final PaymentSaga saga;
    private final OpenTransactionStore openTransactions;
    private final SagaCounters counters;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile Thread worker;

    public SagaResolver(SaleWriter sales, PaymentSaga saga, OpenTransactionStore openTransactions,
                        SagaCounters counters) {
        this.sales = sales;
        this.saga = saga;
        this.openTransactions = openTransactions;
        this.counters = counters;
    }

    /** A live payment got no answer from inventory: finish it in the background. */
    void handOff(String transactionId) {
        queue.add(transactionId);
    }

    /** Pending payments still queued or in progress (for tests and logs). */
    int backlog() {
        return queue.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    void startAndRecover() {
        Thread t = new Thread(this::run, "saga-resolver");
        t.setDaemon(true);
        t.start();
        worker = t;
        try {
            List<String> pending = sales.pendingIds();
            if (!pending.isEmpty()) {
                log.warn("recovery: {} payment(s) were in progress when the previous process stopped: {}",
                        pending.size(), pending);
                counters.recovered.addAndGet(pending.size());
                queue.addAll(pending);
            } else {
                log.info("recovery: no unfinished payments");
            }
        } catch (RuntimeException e) {
            log.error("recovery: could not read pending payments", e);
        }
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            String id;
            try {
                id = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                resolve(id);
            } catch (RuntimeException e) {
                // only on shutdown (interrupted); otherwise resolve() never gives up
                log.warn("resolver stopped before finishing {}: {}", id, e.toString());
                return;
            }
        }
    }

    private void resolve(String id) {
        PendingSale sale = sales.loadPending(id);
        if (sale == null) {
            // already COMPLETED (e.g. tx B landed just before a crash)
            openTransactions.remove(id);
            return;
        }
        Map<String, Integer> applied = saga.commitStock(sale, null);   // no time limit
        saga.finishSale(id, applied, null);
        openTransactions.remove(id);
        counters.resolved.incrementAndGet();
        log.info("resolved payment {}: stock committed and sale recorded", id);
    }

    @PreDestroy
    void stop() {
        if (worker != null) {
            worker.interrupt();
        }
    }
}
