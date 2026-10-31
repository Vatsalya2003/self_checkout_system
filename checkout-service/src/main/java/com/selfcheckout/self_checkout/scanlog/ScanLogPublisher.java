package com.selfcheckout.self_checkout.scanlog;

import com.selfcheckout.self_checkout.remote.RecordedScan;
import com.selfcheckout.self_checkout.remote.ScanSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CHECKOUT-SERVICE, scan-log layer. Hands every scan to analytics-service.
 *
 *   request thread --offer (<= 10 ms)--> [bounded queue] --> writer thread
 *       --> one ScansRecorded message per batch, publisher-confirmed
 *       --> RabbitMQ analytics.scans --> analytics numbers and stores them
 *
 * Week 6: Week 5 numbered every scan here and pushed the batches to analytics
 * over HTTP. The batches now go through the broker, and analytics assigns the
 * seq itself as it stores them -- so this class no longer numbers anything,
 * and no longer asks analytics where the numbering is after a restart (Week
 * 5's GET /internal/scans/max-seq is gone).
 *
 * The rules, and why each one exists:
 *
 *  1. A scan never waits on analytics or the broker. The request thread only
 *     offers to a bounded queue and gives up after 10 ms (the same limit as
 *     Week 3's ingest pipe). If the queue is full the event is dropped and
 *     COUNTED; the scan itself still succeeds.
 *
 *  2. Batches are collected for up to max-linger-ms (default 50 ms) or 1,000
 *     scans, whichever comes first, so a busy run sends a few big messages a
 *     second instead of thousands of tiny ones (and analytics does a few big
 *     INSERTs, each one a commit).
 *
 *  3. Batches are published in order, one at a time: batch N+1 is built only
 *     after the broker has CONFIRMED batch N. Analytics has a single consumer,
 *     so it stores (and numbers) them in exactly this order.
 *
 *  4. A batch that is not confirmed is re-sent with the SAME batchId until it
 *     is. If the first copy did reach the queue after all, analytics finds the
 *     batch id already stored and skips the second one -- no duplicates.
 *
 *  5. If the broker is down, customers keep being served: scans wait in the
 *     bounded queue (dropped and counted only if it fills) and the writer
 *     keeps retrying the same batch until the broker is back.
 *
 * The write logic lives in writeOnce(), apart from the thread, so the tests
 * can drive it directly without timing.
 */
@Component
public class ScanLogPublisher implements ScanRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScanLogPublisher.class);

    static final int QUEUE_CAPACITY = 65_536;     // ~6 s of scans at stress rates
    static final int MAX_BATCH = 1_000;           // analytics inserts a batch as one 3,000-parameter INSERT
    static final long OFFER_MILLIS = 10;          // the most a request thread ever waits
    static final long POLL_MILLIS = 50;           // idle wake-up: bounds the added latency
    private static final long METRICS_INTERVAL_MILLIS = 10_000;
    private static final long SHUTDOWN_RETRY_MILLIS = 30_000;

    private final ScanSink sink;
    private final BlockingQueue<ScanEvent> queue;
    private final long offerMillis;
    private final long lingerMillis;

    private volatile boolean stopping = false;
    private volatile Thread writerThread;
    private volatile Thread metricsThread;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong writeErrors = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private volatile int maxDepth = 0;

    @Autowired
    public ScanLogPublisher(ScanSink sink,
                            @Value("${selfcheckout.scan-log.max-linger-ms:50}") long lingerMillis) {
        this(sink, QUEUE_CAPACITY, OFFER_MILLIS, lingerMillis);
    }

    /** For tests: a small queue, a short offer wait, and usually no linger. */
    ScanLogPublisher(ScanSink sink, int capacity, long offerMillis, long lingerMillis) {
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerMillis = offerMillis;
        this.lingerMillis = lingerMillis;
    }

    @PostConstruct
    public void start() {
        Thread w = new Thread(this::writerLoop, "scanlog-writer");
        w.setDaemon(true);
        w.start();
        writerThread = w;
        Thread m = new Thread(this::metricsLoop, "scanlog-metrics");
        m.setDaemon(true);
        m.start();
        metricsThread = m;
        log.info("scan-log publisher started: batches of up to {} scans or {} ms, published to {}",
                MAX_BATCH, lingerMillis, "analytics.scans");
    }

    // ---- request thread side ---------------------------------------------

    @Override
    public void recordScan(String sku) {
        try {
            if (queue.offer(new ScanEvent(sku, Instant.now()), offerMillis, TimeUnit.MILLISECONDS)) {
                accepted.incrementAndGet();
                int d = queue.size();
                if (d > maxDepth) {
                    maxDepth = d;     // racy by a hair; it is a high-water mark we only report
                }
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();        // never swallow the flag
        }
        long n = dropped.incrementAndGet();
        // One log line per drop would itself become a bottleneck.
        if (n == 1 || n % 10_000 == 0) {
            log.warn("scan-log queue full ({} deep) -- {} scan event(s) dropped so far; "
                     + "checkout is unaffected, analytics will miss them", queue.size(), n);
        }
    }

    // ---- writer thread side ----------------------------------------------

    private void writerLoop() {
        try {
            while (true) {
                boolean wrote = writeOnce(POLL_MILLIS);
                if (!wrote && stopping && queue.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("scan-log writer stopped: {}", summary());
    }

    /**
     * Wait up to pollMillis for one event, then keep collecting for up to
     * lingerMillis more (or until MAX_BATCH scans), and publish them as one
     * batch. Returns false if nothing arrived.
     */
    boolean writeOnce(long pollMillis) throws InterruptedException {
        ScanEvent first = queue.poll(pollMillis, TimeUnit.MILLISECONDS);
        if (first == null) {
            return false;
        }
        List<ScanEvent> events = new ArrayList<>(MAX_BATCH);
        events.add(first);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lingerMillis);
        while (events.size() < MAX_BATCH) {
            queue.drainTo(events, MAX_BATCH - events.size());
            long remaining = deadline - System.nanoTime();
            if (events.size() >= MAX_BATCH || remaining <= 0 || stopping) {
                break;
            }
            ScanEvent next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;                              // linger expired with nothing new
            }
            events.add(next);
        }

        List<RecordedScan> scans = new ArrayList<>(events.size());
        for (ScanEvent e : events) {
            scans.add(new RecordedScan(e.sku(), e.scannedAt()));
        }
        // The batch's identity is fixed here, once: every retry below sends
        // this same id, which is what lets analytics drop a second copy.
        publishWithRetry(UUID.randomUUID().toString(), scans);
        return true;
    }

    /** Re-send the same batch (same batchId) until the broker confirms it. */
    private void publishWithRetry(String batchId, List<RecordedScan> scans) throws InterruptedException {
        long backoff = 100;
        long gaveUpAt = Long.MAX_VALUE;
        while (true) {
            try {
                sink.push(batchId, scans);
                written.addAndGet(scans.size());
                batches.incrementAndGet();
                return;
            } catch (RuntimeException e) {
                writeErrors.incrementAndGet();
                log.warn("scan batch {} ({} scans) not published; retrying the same batch: {}",
                        batchId, scans.size(), e.toString());
                if (stopping && gaveUpAt == Long.MAX_VALUE) {
                    gaveUpAt = System.currentTimeMillis() + SHUTDOWN_RETRY_MILLIS;
                }
                if (System.currentTimeMillis() > gaveUpAt) {
                    // Only while shutting down with the broker gone.
                    lost.addAndGet(scans.size());
                    log.error("giving up on {} scans at shutdown (broker unreachable)", scans.size());
                    return;
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 2_000);
            }
        }
    }

    private void metricsLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(METRICS_INTERVAL_MILLIS);
                log.info(summary());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One line, cheap to read in a log tail during a load run. */
    public String summary() {
        long b = batches.get();
        return String.format("scan-log publisher | accepted=%d written=%d dropped=%d lost=%d | batches=%d avgBatch=%d "
                        + "| queue depth=%d max=%d cap=%d | writeErrors=%d",
                accepted.get(), written.get(), dropped.get(), lost.get(), b, b == 0 ? 0 : written.get() / b,
                queue.size(), maxDepth, queue.size() + queue.remainingCapacity(), writeErrors.get());
    }

    /**
     * Drain and stop: everything already queued is published before the
     * writer exits. Runs before the broker connection closes, because Spring
     * destroys beans in reverse dependency order.
     */
    @PreDestroy
    public void stop() {
        stopping = true;
        if (writerThread != null) {
            try {
                writerThread.join(SHUTDOWN_RETRY_MILLIS + 5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (metricsThread != null) {
            metricsThread.interrupt();
        }
        log.info("scan-log publisher stopped: {}", summary());
    }

    // ---- read-only views for tests and logging ----------------------------
    long accepted()    { return accepted.get(); }
    long dropped()     { return dropped.get(); }
    long written()     { return written.get(); }
    long writeErrors() { return writeErrors.get(); }
    int queueDepth()   { return queue.size(); }
}
