package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogReader;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FILTER 1 of 4 -- the source. Replaces Week 3's ingest filter.
 *
 * In the single application, scans reached analytics through an in-process
 * queue. Checkout and analytics are now separate processes that must not call
 * each other, so the hand-off goes through the shared database:
 * checkout-service appends every scan to scan_log with a gapless sequence
 * number, and this filter reads them back in exactly that order and puts them
 * on the window pipe.
 *
 *   SELECT seq, sku, scanned_at FROM scan_log WHERE seq > :last ORDER BY seq LIMIT 5000
 *
 * Two rules make the window bounds correct:
 *  - ORDER: rows are handed downstream strictly by seq, so the window filter's
 *    "seq % 500 == 0" boundaries are the same scans they were in Week 3.
 *  - NO GAPS: every row must be last + 1. The writer in checkout-service
 *    commits batches one at a time in seq order, so a gap should be
 *    impossible -- but if one ever appears this filter STOPS at it and waits
 *    rather than skip a scan and silently shift every window after it.
 *
 * The thread loop is kept apart from pollOnce(), so the logic can be tested
 * without threads or a database.
 */
public class ScanLogPoller implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScanLogPoller.class);

    static final int PAGE_SIZE = 5_000;
    /** How long to sleep when caught up. Bounds the added lag at ~50 ms. */
    static final long IDLE_MILLIS = 50;
    private static final long ERROR_BACKOFF_MILLIS = 500;

    private final ScanLogReader reader;
    private final Pipe<SequencedScanEvent> out;
    private final PipelineMetrics metrics;

    private volatile long lastSeq;              // highest seq handed downstream
    private volatile boolean running = true;
    private volatile boolean stalledAtGap = false;

    // Lag evidence: how long after checkout scanned an item analytics read it.
    private final AtomicLong rowsRead = new AtomicLong();
    private final AtomicLong gapStalls = new AtomicLong();
    private volatile long maxReadLagMillis = 0;
    private final AtomicLong readLagMillisTotal = new AtomicLong();

    public ScanLogPoller(ScanLogReader reader, Pipe<SequencedScanEvent> out,
                         PipelineMetrics metrics, long startAfterSeq) {
        this.reader = reader;
        this.out = out;
        this.metrics = metrics;
        this.lastSeq = startAfterSeq;
    }

    /**
     * One read of scan_log. Hands every contiguous row downstream and returns
     * how many it handed over; stops early (and returns) at a gap.
     */
    public int pollOnce() throws InterruptedException {
        List<ScanLogRow> rows = reader.after(lastSeq, PAGE_SIZE);
        int handed = 0;
        long now = Instant.now().toEpochMilli();
        for (ScanLogRow r : rows) {
            if (r.seq() != lastSeq + 1) {
                if (!stalledAtGap) {
                    gapStalls.incrementAndGet();
                    log.warn("scan_log gap: expected seq {} but next row is {}; holding at {} until it appears",
                            lastSeq + 1, r.seq(), lastSeq);
                }
                stalledAtGap = true;
                return handed;
            }
            stalledAtGap = false;
            metrics.recordIn("poller");
            out.put(new SequencedScanEvent(r.seq(), r.sku(), r.scannedAt()));
            metrics.recordOut("poller");
            lastSeq = r.seq();
            handed++;
            long lag = now - r.scannedAt().toEpochMilli();
            readLagMillisTotal.addAndGet(lag);
            if (lag > maxReadLagMillis) {
                maxReadLagMillis = lag;
            }
        }
        rowsRead.addAndGet(handed);
        return handed;
    }

    @Override
    public void run() {
        log.info("pipeline stage 'poller' started after seq {}", lastSeq);
        try {
            while (running) {
                int n;
                try {
                    n = pollOnce();
                } catch (RuntimeException e) {
                    // The database being briefly unavailable must not kill the
                    // stage; it simply tries again from the same seq.
                    metrics.recordError("poller");
                    log.warn("pipeline stage 'poller' read failed; retrying from seq {}", lastSeq, e);
                    Thread.sleep(ERROR_BACKOFF_MILLIS);
                    continue;
                }
                if (n < PAGE_SIZE) {
                    Thread.sleep(IDLE_MILLIS);    // caught up (or at a gap): wait for more
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            out.poison();                         // let window, rank and sink drain and stop
            log.info("pipeline stage 'poller' stopped at seq {}", lastSeq);
        }
    }

    public void stop() { running = false; }

    public long lastSeq()          { return lastSeq; }
    public long rowsRead()         { return rowsRead.get(); }
    public long gapStalls()        { return gapStalls.get(); }
    public boolean stalledAtGap()  { return stalledAtGap; }
    public long maxReadLagMillis() { return maxReadLagMillis; }

    /** Mean milliseconds between checkout scanning an item and this filter reading it. */
    public long meanReadLagMillis() {
        long n = rowsRead.get();
        return n == 0 ? 0 : readLagMillisTotal.get() / n;
    }
}
