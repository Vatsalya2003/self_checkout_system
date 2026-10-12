package com.selfcheckout.self_checkout.scanlog;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogBatchWriter;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CHECKOUT-SERVICE, scan-log layer. Publishes every scan to scan_log, which is
 * how analytics-service hears about scans without the two services calling
 * each other.
 *
 *   request thread --offer (<= 10 ms)--> [bounded queue] --> writer thread
 *       --> numbers each scan, then one multi-row INSERT per batch --> scan_log
 *
 * The rules, and why each one exists:
 *
 *  1. A scan never waits on analytics. The request thread only offers to a
 *     bounded queue and gives up after 10 ms (the same limit as Week 3's
 *     ingest pipe). If the queue is full the event is dropped and COUNTED;
 *     the scan itself still succeeds.
 *
 *  2. seq is gapless and in scan order. Exactly one thread assigns it, and
 *     only when it builds a batch -- so a dropped event never uses a number.
 *     The window filter's "seq % 500 == 0" boundaries depend on that.
 *
 *  3. Batches are collected for up to max-linger-ms (default 50 ms) or 1,000
 *     rows, whichever comes first, so a busy run writes a few big INSERTs a
 *     second instead of thousands of tiny ones -- each INSERT is a commit, and
 *     every commit is a WAL flush competing with the payments' own commits.
 *     Costs at most that much extra analytics lag; changes no window.
 *
 *  4. Committed seqs are always exactly 1..K with no holes. The writer does
 *     one INSERT at a time (autocommit) and only builds batch N+1 after batch
 *     N has committed. analytics-service can therefore read "seq > last ORDER
 *     BY seq" and never see a later scan before an earlier one is visible.
 *
 *  5. A failed write is retried with the SAME seqs, never renumbered, so a
 *     database hiccup cannot open a hole. The counter only moves on after a
 *     batch has committed.
 *
 *  6. After a restart the counter carries on from MAX(seq) in scan_log, so a
 *     restarted checkout-service keeps the sequence gapless.
 *
 * The write logic lives in writeOnce(), apart from the thread, so the tests
 * can drive it directly without timing.
 */
@Component
public class ScanLogPublisher implements ScanRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScanLogPublisher.class);

    static final int QUEUE_CAPACITY = 65_536;     // ~6 s of scans at stress rates
    static final int MAX_BATCH = 1_000;           // 3,000 bind parameters per INSERT
    static final long OFFER_MILLIS = 10;          // the most a request thread ever waits
    static final long POLL_MILLIS = 50;           // idle wake-up: bounds the added latency
    private static final long METRICS_INTERVAL_MILLIS = 10_000;
    private static final long SHUTDOWN_RETRY_MILLIS = 30_000;

    private final ScanLogBatchWriter writer;
    private final BlockingQueue<ScanEvent> queue;
    private final long offerMillis;
    private final long lingerMillis;

    private long nextSeq;                         // owned by the writer thread only
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
    private volatile long lastSeq = 0;

    @Autowired
    public ScanLogPublisher(ScanLogBatchWriter writer,
                            @Value("${selfcheckout.scan-log.max-linger-ms:50}") long lingerMillis) {
        this(writer, QUEUE_CAPACITY, OFFER_MILLIS, lingerMillis);
    }

    /** For tests: a small queue, a short offer wait, and usually no linger. */
    ScanLogPublisher(ScanLogBatchWriter writer, int capacity, long offerMillis, long lingerMillis) {
        this.writer = writer;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerMillis = offerMillis;
        this.lingerMillis = lingerMillis;
    }

    @PostConstruct
    public void start() {
        resumeFrom(writer.maxSeq());
        Thread w = new Thread(this::writerLoop, "scanlog-writer");
        w.setDaemon(true);
        w.start();
        writerThread = w;
        Thread m = new Thread(this::metricsLoop, "scanlog-metrics");
        m.setDaemon(true);
        m.start();
        metricsThread = m;
        log.info("scan-log publisher started: next seq {}, batches of up to {} rows or {} ms",
                nextSeq, MAX_BATCH, lingerMillis);
    }

    /** Continue numbering after the highest seq already in scan_log. */
    void resumeFrom(long maxSeqInTable) {
        nextSeq = maxSeqInTable + 1;
        lastSeq = maxSeqInTable;
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
     * lingerMillis more (or until MAX_BATCH rows), number them and write them
     * in one INSERT. Returns false if nothing arrived.
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

        // Numbered here, by the only thread that numbers anything.
        List<ScanLogRow> rows = new ArrayList<>(events.size());
        long seq = nextSeq;
        for (ScanEvent e : events) {
            rows.add(new ScanLogRow(seq++, e.sku(), e.scannedAt()));
        }
        insertWithRetry(rows);
        return true;
    }

    /** Retry the same rows (same seqs) until they commit; move the counter only after that. */
    private void insertWithRetry(List<ScanLogRow> rows) throws InterruptedException {
        long backoff = 100;
        long gaveUpAt = Long.MAX_VALUE;
        while (true) {
            try {
                writer.insertBatch(rows);
                nextSeq = rows.get(rows.size() - 1).seq() + 1;
                lastSeq = nextSeq - 1;
                written.addAndGet(rows.size());
                batches.incrementAndGet();
                return;
            } catch (RuntimeException e) {
                writeErrors.incrementAndGet();
                log.warn("scan_log insert of seq {}..{} failed; retrying with the same seqs",
                        rows.get(0).seq(), rows.get(rows.size() - 1).seq(), e);
                if (stopping && gaveUpAt == Long.MAX_VALUE) {
                    gaveUpAt = System.currentTimeMillis() + SHUTDOWN_RETRY_MILLIS;
                }
                if (System.currentTimeMillis() > gaveUpAt) {
                    // Only while shutting down with the database gone. The
                    // counter does not move, so the table still has no gap.
                    lost.addAndGet(rows.size());
                    log.error("giving up on {} scan_log rows at shutdown", rows.size());
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
                        + "| queue depth=%d max=%d cap=%d | lastSeq=%d | writeErrors=%d",
                accepted.get(), written.get(), dropped.get(), lost.get(), b, b == 0 ? 0 : written.get() / b,
                queue.size(), maxDepth, queue.size() + queue.remainingCapacity(), lastSeq, writeErrors.get());
    }

    /**
     * Drain and stop: everything already queued is written before the writer
     * exits. Runs before the scan-log pool closes, because Spring destroys
     * beans in reverse dependency order.
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
    long lastSeq()     { return lastSeq; }
    int queueDepth()   { return queue.size(); }
}
