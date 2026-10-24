package com.selfcheckout.self_checkout.transactions;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// CHECKOUT-SERVICE, transactions layer. What the payment saga did, as one log
// line every 10 s ("saga | ..."), so a load run can be read from the log tail:
//
//   payments        completed through the normal path
//   stockRetries    extra stock-commit attempts after a failed one
//   replayed        commits inventory answered from its stored result
//                   (a retry or recovery landing on an already-committed id)
//   rolledBack      inventory certainly never committed -> 503, basket reopened
//   ambiguous       no answer from inventory -> 503, handed to the resolver
//   resolved        finished later by the resolver (ambiguous + recovered)
//   recovered       PENDING payments found at startup (a crash mid-saga)
//   finishRetries   extra attempts at tx B (the local write after the pivot)
//   stock call      time for a successful commit call, mean and max
@Component
public class SagaCounters {

    private static final Logger log = LoggerFactory.getLogger(SagaCounters.class);

    final AtomicLong payments = new AtomicLong();
    final AtomicLong stockRetries = new AtomicLong();
    final AtomicLong replayed = new AtomicLong();
    final AtomicLong rolledBack = new AtomicLong();
    final AtomicLong ambiguous = new AtomicLong();
    final AtomicLong resolved = new AtomicLong();
    final AtomicLong recovered = new AtomicLong();
    final AtomicLong finishRetries = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong callNanos = new AtomicLong();
    private final AtomicLong maxCallNanos = new AtomicLong();

    private volatile boolean haltHookOn;
    private volatile Thread reporter;

    void recordCall(long nanos) {
        calls.incrementAndGet();
        callNanos.addAndGet(nanos);
        maxCallNanos.accumulateAndGet(nanos, Math::max);
    }

    void haltHook(boolean on) {
        this.haltHookOn = on;
    }

    @PostConstruct
    void start() {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10_000);
                    log.info(summary());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "saga-metrics");
        t.setDaemon(true);
        t.start();
        reporter = t;
    }

    @PreDestroy
    void stop() {
        log.info(summary());
        if (reporter != null) {
            reporter.interrupt();
        }
    }

    public String summary() {
        long n = calls.get();
        return String.format("saga | payments=%d stockRetries=%d replayed=%d rolledBack=%d ambiguous=%d "
                        + "resolved=%d recovered=%d finishRetries=%d | stock call mean=%dus max=%dms | haltHook=%s",
                payments.get(), stockRetries.get(), replayed.get(), rolledBack.get(), ambiguous.get(),
                resolved.get(), recovered.get(), finishRetries.get(),
                n == 0 ? 0 : callNanos.get() / n / 1_000, maxCallNanos.get() / 1_000_000,
                haltHookOn ? "ON" : "off");
    }
}
