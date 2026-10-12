package com.selfcheckout.self_checkout.transactions;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TRANSACTIONS layer. Splits a checkout's database time into the two parts
 * that behave completely differently under load.
 *
 * Why this exists: "complete takes 80 ms at 100 stations" is not actionable,
 * because almost all of it is a queue. Two numbers are:
 *
 *  - LOCK WAIT: how long the bulk `FOR NO KEY UPDATE` statement took. On a
 *    hot SKU this is almost entirely time spent queued behind other
 *    checkouts, and it grows with the number of stations.
 *
 *  - CRITICAL SECTION: how long this checkout then HELD those rows, from the
 *    lock being granted to commit. This one should NOT grow with load -- it
 *    is the server's own work. It is also the thing that sets the ceiling:
 *    every basket containing SKU-000001 has to pass through this window one
 *    at a time, so the maximum possible throughput is roughly
 *        1 / (critical section x fraction of baskets holding that SKU).
 *
 * Shortening the critical section is therefore the only change that can raise
 * the ceiling; shortening the wait is not something the server can do
 * directly.
 */
@Component
public class CheckoutTimings {

    private static final Logger log = LoggerFactory.getLogger(CheckoutTimings.class);

    private static final long REPORT_INTERVAL_MILLIS = 10_000;

    private final AtomicLong completions = new AtomicLong();
    private final AtomicLong lockWaitNanos = new AtomicLong();
    private final AtomicLong criticalSectionNanos = new AtomicLong();

    private volatile Thread reporter;

    // Same shape as the analytics pipeline's reporter: a daemon thread that
    // prints one line every 10 s, so a load run can be read from the log tail
    // without attaching anything.
    @PostConstruct
    void startReporter() {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(REPORT_INTERVAL_MILLIS);
                    logSummary();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "checkout-timings");
        t.setDaemon(true);
        t.start();
        reporter = t;
    }

    @PreDestroy
    void stopReporter() {
        logSummary();
        if (reporter != null) {
            reporter.interrupt();
        }
    }

    void record(long lockWait, long criticalSection) {
        completions.incrementAndGet();
        lockWaitNanos.addAndGet(lockWait);
        criticalSectionNanos.addAndGet(criticalSection);
    }

    public long completions() { return completions.get(); }

    /** Mean microseconds spent waiting for the bulk item lock. */
    public long meanLockWaitMicros() { return mean(lockWaitNanos); }

    /** Mean microseconds the item rows were held, lock granted to commit. */
    public long meanCriticalSectionMicros() { return mean(criticalSectionNanos); }

    private long mean(AtomicLong total) {
        long n = completions.get();
        return n == 0 ? 0 : total.get() / n / 1_000;
    }

    public void logSummary() {
        long n = completions.get();
        if (n == 0) {
            return;
        }
        log.info("checkout timings | completions={} | mean lockWait={}us criticalSection={}us "
                 + "| implied ceiling if 66% of baskets share the hottest SKU: {} tx/s",
                n, meanLockWaitMicros(), meanCriticalSectionMicros(),
                meanCriticalSectionMicros() == 0
                        ? "n/a"
                        : String.valueOf((long) (1_000_000.0 / meanCriticalSectionMicros() / 0.66)));
    }
}
